const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const crypto = require('node:crypto');

initializeApp();
const db = getFirestore();

function normalizeRoomName(value) {
  return String(value || '').normalize('NFKC').trim().toLocaleLowerCase('ru-RU');
}
function roomIdFor(name) {
  return crypto.createHash('sha256').update(normalizeRoomName(name)).digest('hex');
}
function passwordHash(password, salt) {
  return crypto.scryptSync(password, salt, 64).toString('hex');
}

exports.createOrJoinRoom = onCall({ enforceAppCheck: false }, async (request) => {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Требуется авторизация.');
  const displayName = String(request.data?.displayName || '').trim().slice(0, 40);
  const roomName = String(request.data?.roomName || '').trim().slice(0, 60);
  const password = String(request.data?.password || '');
  if (!displayName || roomName.length < 4 || password.length < 4 || password.length > 128) {
    throw new HttpsError('invalid-argument', 'Проверьте имя, название комнаты и пароль (минимум 4 символа).');
  }
  const roomId = roomIdFor(roomName);
  const roomRef = db.collection('rooms').doc(roomId);
  const uid = request.auth.uid;
  const memberRef = roomRef.collection('members').doc(uid);
  await db.runTransaction(async (tx) => {
    const roomSnap = await tx.get(roomRef);
    let salt;
    let hash;
    if (!roomSnap.exists) {
      salt = crypto.randomBytes(16).toString('hex');
      hash = passwordHash(password, salt);
      tx.create(roomRef, {
        name: roomName,
        normalizedName: normalizeRoomName(roomName),
        passwordSalt: salt,
        passwordHash: hash,
        createdAt: FieldValue.serverTimestamp()
      });
    } else {
      const room = roomSnap.data();
      salt = room.passwordSalt;
      hash = passwordHash(password, salt);
      const supplied = Buffer.from(hash, 'hex');
      const expected = Buffer.from(room.passwordHash, 'hex');
      if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) {
        throw new HttpsError('permission-denied', 'Неверный пароль комнаты.');
      }
    }
    tx.set(memberRef, {
      displayName,
      sharingLocation: true,
      joinedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp()
    });
  });
  return { roomId, roomName };
});
