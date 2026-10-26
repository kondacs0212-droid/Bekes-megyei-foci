package hu.bekesmegyeifoci.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.*;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class MainActivity extends Activity {
  private static final int NOTIFICATION_PERMISSION_REQUEST = 1001;
  private WebView web;
  private final FirebaseFirestore db = FirebaseFirestore.getInstance();
  private final FirebaseAuth auth = FirebaseAuth.getInstance();
  private ListenerRegistration matchListener, playerListener, adminListener;

  @Override public void onCreate(Bundle b) {
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
    FirebaseMessaging.getInstance().subscribeToTopic("kardos_ksk")
      .addOnFailureListener(e -> send("error", "Értesítési feliratkozás sikertelen: " + e.getMessage()));
    if (Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
    }
  }

  private void send(String event, Object payload) {
    String json = JSONObject.quote(String.valueOf(payload));
    runOnUiThread(() -> web.evaluateJavascript("window.receiveNative(" + JSONObject.quote(event) + "," + json + ")", null));
  }

  private void startListeners() {
    matchListener = db.collection("matches").orderBy("kickoff").addSnapshotListener((snap, err) -> emitDocs("matches", snap, err));
    playerListener = db.collection("players").addSnapshotListener((snap, err) -> emitDocs("players", snap, err));
    updateAuth();
  }

  private void emitDocs(String name, QuerySnapshot snap, FirebaseFirestoreException err) {
    if (err != null) { send("error", err.getMessage()); return; }
    if (snap == null) return;
    JSONArray a = new JSONArray();
    for (DocumentSnapshot d : snap.getDocuments()) {
      JSONObject obj = new JSONObject(d.getData());
      try { obj.put("id", d.getId()); } catch (Exception ignored) {}
      a.put(obj);
    }
    send(name, a.toString());
  }

  private void updateAuth() {
    if (adminListener != null) { adminListener.remove(); adminListener = null; }
    if (auth.getCurrentUser() == null) {
      send("auth", "{\"signedIn\":false,\"admin\":false}");
      return;
    }
    String uid = auth.getCurrentUser().getUid();
    String email = auth.getCurrentUser().getEmail();
    adminListener = db.collection("admins").document(uid).addSnapshotListener((doc, err) -> {
      boolean admin = err == null && doc != null && doc.exists() && Boolean.TRUE.equals(doc.getBoolean("enabled"));
      JSONObject o = new JSONObject();
      try {
        o.put("signedIn", true);
        o.put("admin", admin);
        o.put("email", email);
        o.put("uid", uid);
      } catch (Exception ignored) {}
      send("auth", o.toString());
      if (err != null) send("error", "Adminjog ellenőrzése sikertelen: " + err.getMessage());
    });
  }

  public class Bridge {
    @JavascriptInterface public void login(String email, String password) {
      runOnUiThread(() -> {
        String e = email == null ? "" : email.trim();
        if (e.isEmpty() || password == null || password.isEmpty()) {
          send("error", "Add meg az e-mail-címedet és a jelszavadat.");
          return;
        }
        auth.signInWithEmailAndPassword(e, password)
          .addOnSuccessListener(r -> { send("error", ""); updateAuth(); })
          .addOnFailureListener(ex -> send("error", "Belépés sikertelen. Ellenőrizd az e-mail-címet és a jelszót."));
      });
    }
    @JavascriptInterface public void logout() {
      runOnUiThread(() -> { auth.signOut(); updateAuth(); });
    }
    @JavascriptInterface public void saveMatch(String id, String json) { save("matches", id, json); }
    @JavascriptInterface public void savePlayer(String id, String json) { save("players", id, json); }
    @JavascriptInterface public void deleteMatch(String id) {
      if (id == null || id.trim().isEmpty()) {
        send("error", "Hiányzik a mérkőzés azonosítója.");
        return;
      }
      com.google.firebase.auth.FirebaseUser user = auth.getCurrentUser();
      if (user == null) {
        send("error", "A törléshez adminbelépés szükséges.");
        return;
      }
      db.collection("admins").document(user.getUid()).get()
        .addOnSuccessListener(doc -> {
          if (!Boolean.TRUE.equals(doc.getBoolean("enabled"))) {
            send("error", "Nincs adminjogosultságod a törléshez.");
            return;
          }
          db.collection("matches").document(id).delete()
            .addOnSuccessListener(v -> send("matchDeleted", id))
            .addOnFailureListener(e -> send("error", "Törlés sikertelen: " + e.getMessage()));
        })
        .addOnFailureListener(e -> send("error", "Adminellenőrzési hiba: " + e.getMessage()));
    }

    private void save(String collection, String id, String json) {
      try {
        JSONObject j = new JSONObject(json);
        Map<String, Object> data = toMap(j);
        if (id == null || id.isEmpty())
          db.collection(collection).add(data).addOnFailureListener(e -> send("error", e.getMessage()));
        else
          db.collection(collection).document(id).set(data).addOnFailureListener(e -> send("error", e.getMessage()));
      } catch (Exception e) { send("error", e.getMessage()); }
    }
  }

  private Map<String, Object> toMap(JSONObject j) throws Exception {
    Map<String, Object> m = new HashMap<>();
    Iterator<String> it = j.keys();
    while (it.hasNext()) {
      String k = it.next(); Object v = j.get(k);
      if (v instanceof JSONObject) v = toMap((JSONObject) v);
      else if (v instanceof JSONArray) {
        JSONArray a = (JSONArray) v; List<Object> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
          Object x = a.get(i); l.add(x instanceof JSONObject ? toMap((JSONObject) x) : x);
        }
        v = l;
      }
      m.put(k, v);
    }
    return m;
  }

  @Override protected void onDestroy() {
    if (matchListener != null) matchListener.remove();
    if (playerListener != null) playerListener.remove();
    if (adminListener != null) adminListener.remove();
    web.destroy();
    super.onDestroy();
  }
}
