package hu.bekesmegyeifoci.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import android.app.Activity;
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
import java.util.*;

public class MainActivity extends Activity {
  private static final int NOTIFICATION_PERMISSION_REQUEST = 1001;
  private static final String NOTIFICATION_WORKER_URL =
      "https://foci-ertesites-indit.kondacs0212.workers.dev/";
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

  private static final String RELEASES_API = "https://api.github.com/repos/kondacs0212-droid/Bekes-foci-frissites/releases/latest";
  private static final String RELEASES_WEB = "https://github.com/kondacs0212-droid/Bekes-foci-frissites/releases";

  private static int compareVersions(String a, String b) {
    String[] aa = a.replaceFirst("^[vV]", "").split("\\.");
    String[] bb = b.replaceFirst("^[vV]", "").split("\\.");
    for (int i = 0; i < Math.max(aa.length, bb.length); i++) {
      int x = i < aa.length ? numericVersionPart(aa[i]) : 0;
      int y = i < bb.length ? numericVersionPart(bb[i]) : 0;
      if (x != y) return Integer.compare(x, y);
    }
    return 0;
  }

  private static int numericVersionPart(String value) {
    try { return Integer.parseInt(value.replaceAll("[^0-9].*$", "")); }
    catch (Exception ignored) { return 0; }
  }

  // A Firebase-mentés sikeres befejezése után indítjuk az értesítési ellenőrzést.
  // A Worker csak a jogosult admin Firebase ID tokenjét fogadja el.
  private void triggerNotifications() {
    FirebaseUser user = auth.getCurrentUser();
    if (user == null) return;
    user.getIdToken(false)
      .addOnSuccessListener(result -> {
        String token = result.getToken();
        if (token == null || token.isEmpty()) {
          send("error", "Értesítésindítás: nem sikerült Firebase tokent lekérni.");
          return;
        }
        new Thread(() -> {
          HttpURLConnection conn = null;
          try {
            conn = (HttpURLConnection) new URL(NOTIFICATION_WORKER_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            try (OutputStream output = conn.getOutputStream()) {
              output.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
              send("error", "Értesítésindítás sikertelen (Cloudflare HTTP " + status + ").");
            }
          } catch (Exception e) {
            send("error", "Értesítésindítási hálózati hiba: " + e.getMessage());
          } finally {
            if (conn != null) conn.disconnect();
          }
        }).start();
      })
      .addOnFailureListener(e -> send("error", "Értesítésindítás: Firebase tokenhiba: " + e.getMessage()));
  }

  public class Bridge {
    @JavascriptInterface public void checkForUpdates() {
      new Thread(() -> {
        HttpURLConnection conn = null;
        try {
          conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
          conn.setConnectTimeout(12000);
          conn.setReadTimeout(12000);
          conn.setRequestProperty("Accept", "application/vnd.github+json");
          conn.setRequestProperty("User-Agent", "BekesMegyeiFoci-Android");
          if (conn.getResponseCode() != 200) throw new Exception("GitHub válasz: " + conn.getResponseCode());
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          try (InputStream input = conn.getInputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) {
              bytes.write(buffer, 0, n);
              if (bytes.size() > 1024 * 1024) throw new Exception("Túl nagy válasz.");
            }
          }
          JSONObject release = new JSONObject(bytes.toString("UTF-8"));
          String tag = release.optString("tag_name", "");
          if (tag.isEmpty()) throw new Exception("Nem található verziószám.");
          String currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
          if (compareVersions(tag, currentVersion) <= 0) {
            send("updateStatus", "Az alkalmazás naprakész (" + currentVersion + ").");
            return;
          }
          JSONArray assets = release.optJSONArray("assets");
          boolean hasApk = false;
          if (assets != null) for (int i = 0; i < assets.length(); i++) {
            if (assets.optJSONObject(i).optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) hasApk = true;
          }
          if (!hasApk) {
            send("updateStatus", "Új verzió (" + tag + ") található, de még nincs hozzá APK feltöltve.");
            return;
          }
          JSONObject result = new JSONObject();
          result.put("version", tag);
          String page = release.optString("html_url", RELEASES_WEB);
          if (!page.startsWith(RELEASES_WEB + "/tag/")) page = RELEASES_WEB;
          result.put("url", page);
          send("updateAvailable", result.toString());
        } catch (Exception e) {
          send("updateStatus", "Nem sikerült ellenőrizni a frissítést: " + e.getMessage());
        } finally { if (conn != null) conn.disconnect(); }
      }).start();
    }

    @JavascriptInterface public void openUpdatePage(String url) {
      if (url == null || !url.startsWith(RELEASES_WEB + "/tag/")) return;
      runOnUiThread(() -> {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { send("updateStatus", "Nem sikerült megnyitni a letöltési oldalt."); }
      });
    }
    @JavascriptInterface public void getFcmToken() {
      runOnUiThread(() -> {
        if (auth.getCurrentUser() == null) {
          send("error", "Az azonosító megtekintéséhez adminbelépés szükséges.");
          return;
        }
        db.collection("admins").document(auth.getCurrentUser().getUid()).get()
          .addOnSuccessListener(doc -> {
            if (!Boolean.TRUE.equals(doc.getBoolean("enabled"))) {
              send("error", "Az azonosító megtekintéséhez adminjog szükséges.");
              return;
            }
            FirebaseMessaging.getInstance().getToken()
              .addOnSuccessListener(token -> send("fcmToken", token))
              .addOnFailureListener(e -> send("error", "FCM azonosító lekérése sikertelen: " + e.getMessage()));
          })
          .addOnFailureListener(e -> send("error", "Adminellenőrzési hiba: " + e.getMessage()));
      });
    }
    @JavascriptInterface public void copyFcmToken(String token) {
      runOnUiThread(() -> {
        if (auth.getCurrentUser() == null || token == null || token.isEmpty()) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(currentToken -> {
          if (!currentToken.equals(token)) return;
          ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
          if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("FCM token", token));
            send("tokenCopied", "Azonosító másolva.");
          }
        });
      });
    }
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
    @JavascriptInterface public void deletePlayer(String id) {
      if (id == null || id.trim().isEmpty()) { send("error", "Hiányzik a játékos azonosítója."); return; }
      FirebaseUser user = auth.getCurrentUser();
      if (user == null) { send("error", "A törléshez adminbelépés szükséges."); return; }
      db.collection("admins").document(user.getUid()).get()
        .addOnSuccessListener(doc -> {
          if (!Boolean.TRUE.equals(doc.getBoolean("enabled"))) { send("error", "Nincs adminjogosultságod a törléshez."); return; }
          db.collection("players").document(id).delete()
            .addOnSuccessListener(v -> send("playerDeleted", id))
            .addOnFailureListener(e -> send("error", "Játékos törlése sikertelen: " + e.getMessage()));
        })
        .addOnFailureListener(e -> send("error", "Adminellenőrzési hiba: " + e.getMessage()));
    }
    @JavascriptInterface public void deleteMatch(String id) {
      if (id == null || id.trim().isEmpty()) {
        send("error", "Hiányzik a mérkőzés azonosítója.");
        return;
      }
      FirebaseUser user = auth.getCurrentUser();
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
        boolean isMatch = "matches".equals(collection);
        if (id == null || id.isEmpty())
          db.collection(collection).add(data)
            .addOnSuccessListener(ref -> { if (isMatch) triggerNotifications(); })
            .addOnFailureListener(e -> send("error", e.getMessage()));
        else
          db.collection(collection).document(id).set(data)
            .addOnSuccessListener(v -> { if (isMatch) triggerNotifications(); })
            .addOnFailureListener(e -> send("error", e.getMessage()));
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
