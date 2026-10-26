const { initializeApp, cert } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

async function main() {
  const secret = process.env.FIREBASE_SERVICE_ACCOUNT;

  if (!secret) {
    throw new Error("Hiányzik a FIREBASE_SERVICE_ACCOUNT");
  }

  let serviceAccount;

  try {
    serviceAccount = JSON.parse(secret);
  } catch (error) {
    throw new Error("Hibás FIREBASE_SERVICE_ACCOUNT JSON");
  }

  initializeApp({
    credential: cert(serviceAccount)
  });

  const db = getFirestore();
  const messaging = getMessaging();

  const matches = await db.collection("matches").get();
  const stateRef = db.collection("_notification_state").doc("matches");
  const previousDoc = await stateRef.get();

  const previous = previousDoc.exists
    ? previousDoc.data()
    : {};

  const current = {};
  const messages = [];

  for (const doc of matches.docs) {
    const m = doc.data();
    const old = previous[doc.id];

    const events = Array.isArray(m.events) ? m.events : [];
    const name = `${m.home || "Hazai"} – ${m.away || "Vendég"}`;
    const score = `${m.homeScore || 0}:${m.awayScore || 0}`;
    const status = m.status || "Tervezett";
    const period = Number(m.period || 1);

    current[doc.id] = {
      status,
      period,
      actualStartedAt: m.actualStartedAt || "",
      events: events.map(e => JSON.stringify(e))
    };

    // Új mérkőzésnél először eltároljuk az állapotot.
    if (!old) continue;

    const notify = (title, body) => {
      messages.push({
        topic: "kardos_ksk",
        notification: { title, body }
      });
    };

    const oldStatus = old.status || "Tervezett";
    const oldPeriod = Number(old.period || 1);

    // MECCSKEZDÉS
    // Nem követeljük meg, hogy a korábbi státusz
    // pontosan "Tervezett" legyen.
    if (
      status === "Élő" &&
      period === 1 &&
      oldStatus !== "Élő" &&
      oldStatus !== "Félidő" &&
      oldStatus !== "Vége"
    ) {
      notify(
        "⚽ Mérkőzés kezdete",
        `${name} • 0:0`
      );
    }

    // FÉLIDŐ
    if (
      status === "Félidő" &&
      oldStatus !== "Félidő"
    ) {
      notify(
        "⏸ Félidő",
        `${name} • ${score}`
      );
    }

    // MÁSODIK FÉLIDŐ
    if (
      status === "Élő" &&
      period === 2 &&
      (
        oldStatus === "Félidő" ||
        oldPeriod !== 2
      )
    ) {
      notify(
        "▶ Második félidő",
        `${name} • ${score}`
      );
    }

    // MÉRKŐZÉS VÉGE
    if (
      status === "Vége" &&
      oldStatus !== "Vége"
    ) {
      notify(
        "🏁 Mérkőzés vége",
        `${name} • ${score}`
      );
    }

    // ESEMÉNYEK
    // Azonosítót használunk, ha van.
    // A régi, azonosító nélküli eseményeket
    // a korábbi sorrendjük alapján ismerjük fel.
    const oldEvents = (old.events || []).map(value => {
      try {
        return JSON.parse(value);
      } catch (error) {
        return null;
      }
    });

    const oldIds = new Set(
      oldEvents
        .filter(e => e && e.id)
        .map(e => String(e.id))
    );

    const oldLegacyCounts = new Map();

    for (const e of oldEvents) {
      if (!e || e.id) continue;

      const key = JSON.stringify(e);
      oldLegacyCounts.set(
        key,
        (oldLegacyCounts.get(key) || 0) + 1
      );
    }

    for (let index = 0; index < events.length; index++) {
      const e = events[index];

      if (!e || !e.type) continue;

      let isNew = false;

      if (e.id) {
        isNew = !oldIds.has(String(e.id));

        // Migráció: régi eseményhez utólag
        // hozzáadott azonosító nem új esemény.
        if (isNew && oldEvents[index] && !oldEvents[index].id) {
          isNew = false;
        }
      } else {
        const key = JSON.stringify(e);
        const count = oldLegacyCounts.get(key) || 0;

        if (count > 0) {
          oldLegacyCounts.set(key, count - 1);
        } else {
          // Régi esemény utólagos szerkesztése
          // ne számítson új eseménynek.
          isNew = index >= oldEvents.length;
        }
      }

      if (!isNew) continue;

      const symbols = {
        "Gól": "⚽",
        "Öngól": "⚽",
        "Sárga lap": "🟨",
        "Piros lap": "🟥",
        "Csere": "🔄"
      };

      if (!symbols[e.type]) continue;

      notify(
        `${symbols[e.type]} ${e.type}!`,
        `${e.minute ?? 0}'. ${e.team || ""} ${e.player || ""} • ${name} • ${score}`
      );
    }
  }

  // A küldés sikeressége után mentjük az állapotot.
  for (const message of messages) {
    const id = await messaging.send(message);
    console.log("Értesítés elküldve:", id);
  }

  await stateRef.set(current);

  console.log(
    `Ellenőrizve: ${matches.size} mérkőzés, ${messages.length} értesítés.`
  );
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
