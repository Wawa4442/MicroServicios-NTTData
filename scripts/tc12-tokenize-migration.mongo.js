/**
 * TC-12 migration: strip PAN/CVV from existing databases.
 *
 * Run against a pre-TC-12 database. Payment methods that were seeded with raw
 * card data are converted into tokenized, display-only records, and order
 * documents stop carrying any card data at all.
 *
 *   mongo <connection-string> tacocloud scripts/tc12-tokenize-migration.mongo.js
 *
 * Idempotent: after the run, no document contains ccNumber/ccCVV, so running
 * it again is a no-op.
 */

// DB name may need to match your spring.data.mongodb.database (default: tacocloud)
db = db.getSiblingDB("tacocloud");

print("paymentMethod documents to fix:",
    db.paymentMethod.countDocuments({ ccNumber: { $exists: true } }));

db.paymentMethod.find({ ccNumber: { $exists: true } }).forEach(function (p) {
  var card = p.ccNumber || "";
  var last4 = card.length >= 4 ? card.slice(-4) : card;
  var brand = "UNKNOWN";
  if (card.startsWith("4")) brand = "VISA";
  else if (card.startsWith("5") || card.startsWith("2")) brand = "MASTERCARD";
  else if (card.startsWith("3")) brand = "AMEX";
  var token = "migrated_" + djb2(card + ":" + (p.ccCVV || ""));

  db.paymentMethod.updateOne(
    { _id: p._id },
    {
      $set: {
        paymentToken: token,
        brand: brand,
        last4: last4,
        expiration: p.ccExpiration || null
      },
      $unset: { ccNumber: "", ccCVV: "", ccExpiration: "" }
    }
  );
  print("paymentMethod " + p._id + " tokenized (last4=" + last4 + ")");
});

print("order documents to fix:",
    db.tacoOrder.countDocuments({ $or: [{ ccNumber: { $exists: true } }, { ccCVV: { $exists: true } }] }));

db.tacoOrder.find({}).forEach(function (o) {
  var userId = o.user && (o.user.id || o.user._id);
  var payment = null;
  if (userId) {
    payment = db.paymentMethod.findOne({ "user.id": userId })
           || db.paymentMethod.findOne({ "user._id": userId });
  }
  var update = {};
  if (payment) {
    update.paymentMethodId = payment._id ? payment._id.toString() : payment.id;
  }
  db.tacoOrder.updateOne(
    { _id: o._id },
    { $set: update, $unset: { ccNumber: "", ccExpiration: "", ccCVV: "" } }
  );
});
print("orders migrated (paymentMethodId linked where a payment method existed)");

function djb2(s) {
  var hash = 5381;
  for (var i = 0; i < s.length; i++) {
    hash = ((hash << 5) + hash + s.charCodeAt(i)) & 0x7fffffff;
  }
  return hash.toString(16);
}