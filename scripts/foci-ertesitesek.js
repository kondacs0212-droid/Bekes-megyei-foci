
const admin = require("firebase-admin");

async function main() {
  const secret = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!secret) throw new Error("Hiányzik a FIREBASE_SERVICE_ACCOUNT");

  admin.initializeApp({
    credential: admin.credential.cert(JSON.parse(secret))
  });

  const db = admin.firestore();
  const messaging = admin.messaging();
  const matches = await db.collection("matches").get();
  const stateRef = db.collection("_notification_state").doc("matches");
  const previousDoc = await stateRef.get();
  const previous = previousDoc.exists ? previousDoc.data() : null;
  const current = {};
  const messages = [];

  for (const doc of matches.docs) {
    const m = doc.data();
    const old = previous?.[doc.id];
    const events = Array.isArray(m.events) ? m.events : [];
    const name = `${m.home || "Hazai"} – ${m.away || "Vendég"}`;
    const score = `${m.homeScore || 0}:${m.awayScore || 0}`;

    current[doc.id] = {
      status: m.status || "Tervezett",
      period: m.period || 1,
      events: events.map(e => JSON.stringify(e))
    };

    if (!old) continue;

    const notify = (title, body) => {
      messages.push({
        topic: "kardos_ksk",
        notification: { title, body }
      });
    };

    if (old.status !== m.status) {
      if (m.status === "Élő" && old.status === "Tervezett")
        notify("⚽ Mérkőzés kezdete", name);
      if (m.status === "Félidő")
        notify("⏸ Félidő", `${name} • ${score}`);
      if (m.status === "Vége")
        notify("🏁 Mérkőzés vége", `${name} • ${score}`);
    }

    if (old.period !== m.period && m.period === 2 && m.status === "Élő")
      notify("▶ Második félidő", name);

    const seen = new Map();
    for (const value of old.events || [])
      seen.set(value, (seen.get(value) || 0) + 1);

    for (const e of events) {
      const key = JSON.stringify(e);
      const count = seen.get(key) || 0;
      if (count) {
        seen.set(key, count - 1);
        continue;
      }

      if (["Gól", "Öngól", "Sárga lap", "Piros lap"].includes(e.type)) {
        notify(
          `${e.type === "Gól" || e.type === "Öngól" ? "⚽" : "🟨"} ${e.type}!`,
          `${e.minute || 0}'. ${e.team || ""} ${e.player || ""} • ${name} • ${score}`
        );
      }
    }
  }

  if (previous) {
    for (const message of messages) {
      const id = await messaging.send(message);
      console.log("Értesítés elküldve:", id);
    }
  }

  await stateRef.set(current);
  console.log(`Ellenőrizve: ${matches.size} mérkőzés, ${messages.length} értesítés.`);
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
