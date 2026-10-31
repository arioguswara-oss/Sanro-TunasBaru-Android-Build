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

test('drawer pulse only enabled in Drawer Lab; ordinary TESTING/release remain disabled', () => {
  const gradle = fs.readFileSync(path.join(root, 'app/build.gradle'), 'utf8');
  assert.ok(gradle.includes("buildConfigField 'boolean', 'CASH_DRAWER_KICK_ENABLED', 'false'"));
  const lab = gradle.split('drawerLab {')[1]?.split(/\n\s*\}/)[0];
  assert.ok(lab, 'Drawer Lab build type exists');
  assert.ok(lab.includes("buildConfigField 'boolean', 'CASH_DRAWER_KICK_ENABLED', 'true'"));
  assert.ok(mainActivity.includes('CASH_DRAWER_KICK_ENABLED = BuildConfig.CASH_DRAWER_KICK_ENABLED'));
  assert.ok(mainActivity.includes('CASH_DRAWER_REQUEST_COOLDOWN_MS = 30000L'));
  const sender = methodBody('private void sendBluetoothCashDrawerTest(', 'private void drawerTestError(');
  const disabledGate = sender.indexOf('if (!CASH_DRAWER_KICK_ENABLED)');
  const byteConstruction = sender.indexOf('new byte[]{0x1B, 0x70');
  const write = sender.indexOf('BluetoothJobWriter.send(');
  assert.ok(disabledGate >= 0 && disabledGate < byteConstruction);
  assert.ok(disabledGate < write, 'default-off guard precedes Bluetooth write');
  assert.match(sender, /drawer-disabled/);
});

test('bridge requires the trusted TESTING settings page and its per-page token', () => {
  const route = methodBody('private boolean isTrustedCashDrawerTestPage(', '/** Test-only control is injected');
  assert.match(route, /isTrustedWebOrigin\(uri\)/);
  assert.match(route, /"\/setting\.html"\.equals\(uri\.getPath\(\)\)/);
  assert.match(route, /"\/sanro-test-setting\.html"\.equals\(uri\.getPath\(\)\)/);
  const sender = methodBody('private void sendBluetoothCashDrawerTest(', 'private void drawerTestError(');
  assert.match(sender, /if \(!isTrustedCashDrawerTestPage\(\)\) return/);
  assert.match(sender, /pageToken == null/);
  assert.match(sender, /!pageToken\.equals\(cashDrawerPageToken\)/);
  assert.match(sender, /printerEvent\("drawer-error", "Sesi halaman Setting sudah berubah/);
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
  assert.match(ui, /getElementById\('bluetooth-printer-setting'\)/);
  assert.match(ui, /getElementById\('setting-bt-test'\)/);
  assert.match(ui, /host\.contains\(anchor\)/);
  assert.match(ui, /anchor\.insertAdjacentElement\('afterend',b\)/);
  assert.doesNotMatch(ui, /position:fixed|document\.body\.appendChild/);
  assert.match(ui, /AndroidBridge\.testBluetoothCashDrawer/);
  assert.doesNotMatch(ui, /printBluetoothReceipt|sendBluetoothReceipt|database|transaction/i);
});

test('Drawer Lab has its own app ID and launcher title without changing the TESTING base ID', () => {
  const gradle = fs.readFileSync(path.join(root, 'app/build.gradle'), 'utf8');
  assert.ok(gradle.includes("applicationId 'com.sanrotech.pos.testing'"));
  assert.ok(gradle.includes("versionName '1.0.7-testing'"));
  assert.ok(gradle.includes('drawerLab {'));
  assert.ok(gradle.includes('initWith debug'));
  assert.ok(gradle.includes("applicationIdSuffix '.drawerlab'"));
  assert.ok(gradle.includes("versionNameSuffix '-drawerlab'"));
  const strings = fs.readFileSync(path.join(root, 'app/src/drawerLab/res/values/strings.xml'), 'utf8');
  assert.ok(strings.includes('<string name="app_name">SANRO Drawer Lab</string>'));
});

test('token remains stable for repeat onPageFinished and is invalidated by a new page start', () => {
  const injection = methodBody('private void injectCashDrawerTestButton(', 'private boolean hasBluetoothPermission(');
  assert.match(injection, /if \(cashDrawerPageToken\.isEmpty\(\)\) cashDrawerPageToken = UUID\.randomUUID\(\)\.toString\(\)/);
  assert.match(mainActivity, /onPageStarted\(WebView view, String url, Bitmap favicon\).*cashDrawerPageToken = ""/);
  assert.doesNotMatch(injection, /^\s*cashDrawerPageToken = UUID\.randomUUID\(\)\.toString\(\);/m);
});
test('missing native response recovers the Drawer Lab button without automatic retry or sending a pulse', () => {
  const injection = methodBody('private void injectCashDrawerTestButton(', 'private boolean hasBluetoothPermission(');
  assert.match(injection, /timeout=setTimeout\(function\(\)/);
  assert.match(injection, /,30000\)/);
  assert.match(injection, /clearTimeout\(timeout\)/);
  assert.match(injection, /Tidak ada respons dari Android/);
  assert.match(injection, /b\.disabled=false/);
  assert.match(injection, /drawer-confirmation/);
  assert.doesNotMatch(injection, /new byte\[\]|BluetoothJobWriter\.send|setInterval\(/);
});

test('native manual confirmation precedes the only Drawer Lab drawer pulse', () => {
  const bridge = mainActivity.slice(mainActivity.indexOf('@JavascriptInterface public void testBluetoothCashDrawer(String pageToken)'));
  assert.ok(bridge.includes('new AlertDialog.Builder(MainActivity.this)'));
  assert.ok(bridge.includes('.setPositiveButton("KIRIM 1 PULSA"'));
  assert.ok(bridge.includes('.setNegativeButton("Batal"'));
  assert.ok(bridge.includes('.setOnCancelListener('));
  assert.ok(bridge.includes('sendBluetoothCashDrawerTest(pageToken)'));
  const sender = methodBody('private void sendBluetoothCashDrawerTest(', 'private void drawerTestError(');
  assert.ok(sender.includes('new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFF}'));
  assert.ok(sender.includes('if (!CASH_DRAWER_KICK_ENABLED)'));
  assert.ok(sender.includes('BluetoothJobWriter.send(socket.getOutputStream(), kick)'));
  assert.ok(!mainActivity.slice(mainActivity.indexOf('private void sendBluetoothTestPrint('), mainActivity.indexOf('private void sendBluetoothCashDrawerTest(')).includes('sendBluetoothCashDrawerTest('));
});
