package hu.bekesmegyeifoci.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.*;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int NOTIFICATION_PERMISSION_REQUEST = 1001;

    private static final String WORKER_URL =
        "https://foci-ertesites-indit.kondacs0212.workers.dev/";

    private WebView web;

    private final FirebaseFirestore db =
        FirebaseFirestore.getInstance();

    private final FirebaseAuth auth =
        FirebaseAuth.getInstance();

    private final ExecutorService networkExecutor =
        Executors.newSingleThreadExecutor();

    private ListenerRegistration matchListener;
    private ListenerRegistration playerListener;
    private ListenerRegistration adminListener;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());

        setContentView(web);

        web.loadUrl("file:///android_asset/index.html");

        web.postDelayed(this::startListeners, 1000);

        setupNotifications();
    }

    private void setupNotifications() {
        FirebaseMessaging.getInstance()
            .subscribeToTopic("kardos_ksk")
            .addOnFailureListener(e ->
                send("error",
                    "Értesítési feliratkozás sikertelen: "
                    + e.getMessage())
            );

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                new String[]{
                    Manifest.permission.POST_NOTIFICATIONS
                },
                NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private void send(String event, Object payload) {
        String json = JSONObject.quote(
            String.valueOf(payload)
        );

        runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript(
                    "window.receiveNative("
                    + JSONObject.quote(event)
                    + ","
                    + json
                    + ")",
                    null
                );
            }
        });
    }

    private void startListeners() {
        matchListener = db.collection("matches")
            .orderBy("kickoff")
            .addSnapshotListener((snap, err) ->
                emitDocs("matches", snap, err)
            );

        playerListener = db.collection("players")
            .addSnapshotListener((snap, err) ->
                emitDocs("players", snap, err)
            );

        updateAuth();
    }

    private void emitDocs(
        String name,
        QuerySnapshot snap,
        FirebaseFirestoreException err
    ) {
        if (err != null) {
            send("error", err.getMessage());
            return;
        }

        if (snap == null) return;

        JSONArray array = new JSONArray();

        for (DocumentSnapshot doc : snap.getDocuments()) {
            JSONObject obj =
                new JSONObject(doc.getData());

            try {
                obj.put("id", doc.getId());
            } catch (Exception ignored) {}

            array.put(obj);
        }

        send(name, array.toString());
    }

    private void updateAuth() {
        if (adminListener != null) {
            adminListener.remove();
            adminListener = null;
        }

        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            send(
                "auth",
                "{\"signedIn\":false,\"admin\":false}"
            );
            return;
        }

        String uid = user.getUid();
        String email = user.getEmail();

        adminListener = db.collection("admins")
            .document(uid)
            .addSnapshotListener((doc, err) -> {

                boolean isAdmin =
                    err == null &&
                    doc != null &&
                    doc.exists() &&
                    Boolean.TRUE.equals(
                        doc.getBoolean("enabled")
                    );

                JSONObject obj = new JSONObject();

                try {
                    obj.put("signedIn", true);
                    obj.put("admin", isAdmin);
                    obj.put("email", email);
                    obj.put("uid", uid);
                } catch (Exception ignored) {}

                send("auth", obj.toString());

                if (err != null) {
                    send(
                        "error",
                        "Adminjog ellenőrzése sikertelen: "
                        + err.getMessage()
                    );
                }
            });
    }

    // Sikeres Firebase-mentés után
    // elindítjuk a GitHub Actions folyamatot.

    private void triggerNotifications() {
        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            send(
                "error",
                "Értesítés nem indult: nincs adminbelépés."
            );
            return;
        }

        user.getIdToken(true)
            .addOnSuccessListener(result -> {

                String idToken = result.getToken();

                if (idToken == null ||
                    idToken.isEmpty()) {

                    send(
                        "error",
                        "Értesítés nem indult: hiányzik a Firebase-token."
                    );
                    return;
                }

                networkExecutor.execute(() ->
                    callWorker(idToken)
                );
            })
            .addOnFailureListener(e ->
                send(
                    "error",
                    "Értesítés indítási hiba: "
                    + e.getMessage()
                )
            );
    }

    private void callWorker(String idToken) {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(WORKER_URL);

            connection =
                (HttpURLConnection)
                    url.openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            connection.setRequestProperty(
                "Authorization",
                "Bearer " + idToken
            );

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            );

            int status =
                connection.getResponseCode();

            if (status >= 200 && status < 300) {

                send(
                    "notificationTriggered",
                    "Értesítési folyamat elindítva."
                );

            } else {

                String detail = "";

                try {
                    InputStream stream =
                        connection.getErrorStream();

                    if (stream != null) {

                        ByteArrayOutputStream output =
                            new ByteArrayOutputStream();

                        byte[] buffer =
                            new byte[1024];

                        int count;

                        while (
                            (count = stream.read(buffer))
                            != -1
                        ) {
                            output.write(
                                buffer, 0, count
                            );
                        }

                        stream.close();

                        detail =
                            output.toString("UTF-8");
                    }

                } catch (Exception ignored) {}

                send(
                    "error",
                    "Értesítés indítási hiba (HTTP "
                    + status + "): " + detail
                );
            }

        } catch (Exception e) {

            send(
                "error",
                "Cloudflare kapcsolódási hiba: "
                + e.getMessage()
            );

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public class Bridge {

        @JavascriptInterface
        public void getFcmToken() {
            runOnUiThread(() -> {

                FirebaseUser user =
                    auth.getCurrentUser();

                if (user == null) {
                    send(
                        "error",
                        "Az azonosító megtekintéséhez adminbelépés szükséges."
                    );
                    return;
                }

                db.collection("admins")
                    .document(user.getUid())
                    .get()
                    .addOnSuccessListener(doc -> {

                        if (!Boolean.TRUE.equals(
                            doc.getBoolean("enabled")
                        )) {
                            send(
                                "error",
                                "Az azonosító megtekintéséhez adminjog szükséges."
                            );
                            return;
                        }

                        FirebaseMessaging
                            .getInstance()
                            .getToken()
                            .addOnSuccessListener(
                                token ->
                                    send(
                                        "fcmToken",
                                        token
                                    )
                            )
                            .addOnFailureListener(
                                e ->
                                    send(
                                        "error",
                                        "FCM azonosító lekérése sikertelen: "
                                        + e.getMessage()
                                    )
                            );
                    })
                    .addOnFailureListener(e ->
                        send(
                            "error",
                            "Adminellenőrzési hiba: "
                            + e.getMessage()
                        )
                    );
            });
        }

        @JavascriptInterface
        public void copyFcmToken(
            String token
        ) {
            runOnUiThread(() -> {

                if (
                    auth.getCurrentUser() == null ||
                    token == null ||
                    token.isEmpty()
                ) return;

                FirebaseMessaging
                    .getInstance()
                    .getToken()
                    .addOnSuccessListener(
                        currentToken -> {

                            if (!currentToken.equals(
                                token
                            )) return;

                            ClipboardManager clipboard =
                                (ClipboardManager)
                                    getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    );

                            if (clipboard != null) {

                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "FCM token",
                                        token
                                    )
                                );

                                send(
                                    "tokenCopied",
                                    "Azonosító másolva."
                                );
                            }
                        }
                    );
            });
        }

        @JavascriptInterface
        public void login(
            String email,
            String password
        ) {
            runOnUiThread(() -> {

                String e =
                    email == null
                    ? ""
                    : email.trim();

                if (
                    e.isEmpty() ||
                    password == null ||
                    password.isEmpty()
                ) {
                    send(
                        "error",
                        "Add meg az e-mail-címedet és a jelszavadat."
                    );
                    return;
                }

                auth.signInWithEmailAndPassword(
                    e, password
                )
                .addOnSuccessListener(r -> {

                    send("error", "");
                    updateAuth();

                })
                .addOnFailureListener(ex ->
                    send(
                        "error",
                        "Belépés sikertelen. Ellenőrizd az e-mail-címet és a jelszót."
                    )
                );
            });
        }

        @JavascriptInterface
        public void logout() {
            runOnUiThread(() -> {
                auth.signOut();
                updateAuth();
            });
        }

        @JavascriptInterface
        public void saveMatch(
            String id,
            String json
        ) {
            save("matches", id, json);
        }

        @JavascriptInterface
        public void savePlayer(
            String id,
            String json
        ) {
            save("players", id, json);
        }

        @JavascriptInterface
        public void deleteMatch(
            String id
        ) {
            if (
                id == null ||
                id.trim().isEmpty()
            ) {
                send(
                    "error",
                    "Hiányzik a mérkőzés azonosítója."
                );
                return;
            }

            FirebaseUser user =
                auth.getCurrentUser();

            if (user == null) {
                send(
                    "error",
                    "A törléshez adminbelépés szükséges."
                );
                return;
            }

            db.collection("admins")
                .document(user.getUid())
                .get()
                .addOnSuccessListener(doc -> {

                    if (!Boolean.TRUE.equals(
                        doc.getBoolean("enabled")
                    )) {
                        send(
                            "error",
                            "Nincs adminjogosultságod a törléshez."
                        );
                        return;
                    }

                    db.collection("matches")
                        .document(id)
                        .delete()
                        .addOnSuccessListener(v ->
                            send(
                                "matchDeleted",
                                id
                            )
                        )
                        .addOnFailureListener(e ->
                            send(
                                "error",
                                "Törlés sikertelen: "
                                + e.getMessage()
                            )
                        );
                })
                .addOnFailureListener(e ->
                    send(
                        "error",
                        "Adminellenőrzési hiba: "
                        + e.getMessage()
                    )
                );
        }

        private void save(
            String collection,
            String id,
            String json
        ) {
            runOnUiThread(() -> {

                FirebaseUser user =
                    auth.getCurrentUser();

                if (user == null) {
                    send(
                        "error",
                        "A mentéshez adminbelépés szükséges."
                    );
                    return;
                }

                db.collection("admins")
                    .document(user.getUid())
                    .get()
                    .addOnSuccessListener(
                        adminDoc -> {

                            if (!Boolean.TRUE.equals(
                                adminDoc.getBoolean(
                                    "enabled"
                                )
                            )) {
                                send(
                                    "error",
                                    "Nincs adminjogosultságod a mentéshez."
                                );
                                return;
                            }

                            try {

                                JSONObject obj =
                                    new JSONObject(json);

                                Map<String, Object> data =
                                    toMap(obj);

                                if (
                                    id == null ||
                                    id.isEmpty()
                                ) {

                                    db.collection(
                                        collection
                                    )
                                    .add(data)
                                    .addOnFailureListener(
                                        e ->
                                            send(
                                                "error",
                                                "Mentési hiba: "
                                                + e.getMessage()
                                            )
                                    );

                                } else {

                                    db.collection(
                                        collection
                                    )
                                    .document(id)
                                    .set(data)
                                    .addOnSuccessListener(v -> {

                                        if (
                                            "matches".equals(
                                                collection
                                            )
                                        ) {
                                            triggerNotifications();
                                        }

                                    })
                                    .addOnFailureListener(
                                        e ->
                                            send(
                                                "error",
                                                "Mentési hiba: "
                                                + e.getMessage()
                                            )
                                    );
                                }

                            } catch (Exception e) {

                                send(
                                    "error",
                                    e.getMessage()
                                );
                            }
                        }
                    )
                    .addOnFailureListener(e ->
                        send(
                            "error",
                            "Adminellenőrzési hiba: "
                            + e.getMessage()
                        )
                    );
            });
        }
    }

    private Map<String, Object> toMap(
        JSONObject obj
    ) throws Exception {

        Map<String, Object> result =
            new HashMap<>();

        Iterator<String> keys =
            obj.keys();

        while (keys.hasNext()) {

            String key =
                keys.next();

            Object value =
                obj.get(key);

            if (value instanceof JSONObject) {

                value =
                    toMap((JSONObject) value);

            } else if (
                value instanceof JSONArray
            ) {

                JSONArray array =
                    (JSONArray) value;

                List<Object> list =
                    new ArrayList<>();

                for (
                    int i = 0;
                    i < array.length();
                    i++
                ) {

                    Object item =
                        array.get(i);

                    list.add(
                        item instanceof JSONObject
                        ? toMap((JSONObject) item)
                        : item
                    );
                }

                value = list;
            }

            result.put(key, value);
        }

        return result;
    }

    @Override
    protected void onDestroy() {

        if (matchListener != null)
            matchListener.remove();

        if (playerListener != null)
            playerListener.remove();

        if (adminListener != null)
            adminListener.remove();

        networkExecutor.shutdown();

        if (web != null)
            web.destroy();

        super.onDestroy();
    }
}
