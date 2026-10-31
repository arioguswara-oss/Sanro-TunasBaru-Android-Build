const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const mainActivity = fs.readFileSync(
  path.join(root, 'app/src/main/java/com/sanrotech/pos/MainActivity.java'),
  'utf8'
);

function methodBody(name, nextMarker) {
  const start = mainActivity.indexOf(name);
  assert.notEqual(start, -1, `${name} exists`);
  const end = mainActivity.indexOf(nextMarker, start);
  assert.notEqual(end, -1, `${nextMarker} follows ${name}`);
  return mainActivity.slice(start, end);
}

test('cash drawer transmission is explicitly disabled by default', () => {
  assert.match(mainActivity, /CASH_DRAWER_KICK_ENABLED\s*=\s*false\s*;/);
  const sender = methodBody('private void sendBluetoothCashDrawerTest(', 'private void drawerTestError(');
  const disabledGate = sender.indexOf('if (!CASH_DRAWER_KICK_ENABLED)');
  const byteConstruction = sender.indexOf('new byte[]{0x1B, 0x70');
  const write = sender.indexOf('BluetoothJobWriter.send(');
  assert.ok(disabledGate >= 0 && disabledGate < byteConstruction);
  assert.ok(disabledGate < write, 'default-off guard precedes Bluetooth write');
  assert.match(sender, /drawer-disabled/);
});

test('bridge requires the dedicated TESTING page and its per-page token', () => {
  assert.match(mainActivity, /"\/sanro-test-setting\.html"\.equals\(uri\.getPath\(\)\)/);
  const sender = methodBody('private void sendBluetoothCashDrawerTest(', 'private void drawerTestError(');
  assert.match(sender, /if \(!isTrustedCashDrawerTestPage\(\).*pageToken == null/s);
  assert.match(sender, /!pageToken\.equals\(cashDrawerPageToken\)/);
  assert.match(mainActivity, /testBluetoothCashDrawer\(String pageToken\)/);
});

test('one-job guard, duplicate cooldown, explicit status, and no retry remain in place', () => {
  const sender = methodBody('private void sendBluetoothCashDrawerTest(', 'private void drawerTestError(');
  assert.match(sender, /cashDrawerBusy\.compareAndSet\(false, true\)/);
  assert.match(sender, /printBusy\.compareAndSet\(false, true\)/);
  assert.match(sender, /CASH_DRAWER_REQUEST_COOLDOWN_MS/);
  assert.match(sender, /drawer-sending/);
  assert.match(sender, /drawer-sent/);
  assert.match(sender, /drawer-error/);
  assert.match(sender, /bukan bukti laci terbuka|bukan bukti/i);
  assert.doesNotMatch(sender, /while\s*\(|for\s*\([^)]*retry|retry\s*\(/i);
});

test('test UI is injected only by the trusted TESTING route and does not use sale or receipt APIs', () => {
  const ui = methodBody('private void injectCashDrawerTestButton(', 'private boolean hasBluetoothPermission(');
  assert.match(ui, /if \(!isTrustedCashDrawerTestPage\(\)\) return/);
  assert.match(ui, /TEST CASH DRAWER/);
  assert.match(ui, /AndroidBridge\.testBluetoothCashDrawer/);
  assert.doesNotMatch(ui, /printBluetoothReceipt|sendBluetoothReceipt|database|transaction/i);
});
