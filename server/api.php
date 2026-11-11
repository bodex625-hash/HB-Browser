<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
function reply(array $data, int $status = 200): never {
    http_response_code($status); echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES); exit;
}
function input(): array { $data = json_decode(file_get_contents('php://input'), true); return is_array($data) ? $data : []; }
function device_hash(string $id): string { return hash('sha256', $id); }
function code_hash(string $code): string { return hash('sha256', strtoupper(trim($code))); }
$adminKey = getenv('HB_ADMIN_KEY') ?: '';
$dbPath = getenv('HB_DB_PATH') ?: (__DIR__ . '/data/licenses.sqlite');
try {
    if (!extension_loaded('pdo_sqlite')) reply(['ok' => false, 'message' => 'SQLite غير مفعّل على الاستضافة'], 500);
    if (!is_dir(dirname($dbPath))) mkdir(dirname($dbPath), 0700, true);
    $db = new PDO('sqlite:' . $dbPath, null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
    $db->exec("CREATE TABLE IF NOT EXISTS licenses (
        code_hash TEXT PRIMARY KEY, suffix TEXT NOT NULL, duration_days INTEGER NOT NULL,
        created_at INTEGER NOT NULL, expires_at INTEGER NULL, device_hash TEXT NULL,
        activated_at INTEGER NULL, token_hash TEXT NULL, revoked INTEGER NOT NULL DEFAULT 0
    )");
} catch (Throwable $e) { reply(['ok' => false, 'message' => 'تعذر تهيئة قاعدة بيانات التراخيص'], 500); }
$in = input(); $action = (string)($in['action'] ?? '');
if ($action === 'activate') {
    $code = strtoupper(trim((string)($in['code'] ?? ''))); $device = trim((string)($in['device_id'] ?? ''));
    if ($code === '' || $device === '') reply(['ok' => false, 'message' => 'بيانات التفعيل ناقصة'], 400);
    $db->beginTransaction();
    $q = $db->prepare('SELECT * FROM licenses WHERE code_hash = ?'); $q->execute([code_hash($code)]);
    $row = $q->fetch(PDO::FETCH_ASSOC);
    if (!$row || (int)$row['revoked'] === 1) { $db->rollBack(); reply(['ok' => false, 'message' => 'الكود غير صالح أو موقوف'], 403); }
    $dh = device_hash($device);
    if ($row['device_hash'] !== null && !hash_equals($row['device_hash'], $dh)) { $db->rollBack(); reply(['ok' => false, 'message' => 'هذا الكود مرتبط بجهاز آخر'], 403); }
    $now = time();
    if ($row['expires_at'] !== null && (int)$row['expires_at'] < $now) { $db->rollBack(); reply(['ok' => false, 'message' => 'انتهت صلاحية الاشتراك'], 403); }
    $expires = $row['expires_at'] === null ? ((int)$row['duration_days'] === 0 ? null : $now + ((int)$row['duration_days'] * 86400)) : (int)$row['expires_at'];
    $token = bin2hex(random_bytes(32));
    $update = $db->prepare('UPDATE licenses SET device_hash = ?, activated_at = COALESCE(activated_at, ?), expires_at = ?, token_hash = ? WHERE code_hash = ?');
    $update->execute([$dh, $now, $expires, hash('sha256', $token), code_hash($code)]); $db->commit();
    reply(['ok' => true, 'token' => $token, 'expires_at' => $expires === null ? 'دائم' : date('Y-m-d', $expires)]);
}
if ($action === 'validate') {
    $token = trim((string)($in['token'] ?? '')); $device = trim((string)($in['device_id'] ?? ''));
    if ($token === '' || $device === '') reply(['ok' => false, 'message' => 'بيانات التحقق ناقصة'], 400);
    $q = $db->prepare('SELECT expires_at, device_hash, revoked FROM licenses WHERE token_hash = ?');
    $q->execute([hash('sha256', $token)]); $row = $q->fetch(PDO::FETCH_ASSOC);
    $valid = $row && (int)$row['revoked'] === 0 && $row['device_hash'] !== null
        && hash_equals($row['device_hash'], device_hash($device))
        && ($row['expires_at'] === null || (int)$row['expires_at'] >= time());
    if (!$valid) reply(['ok' => false, 'message' => 'الاشتراك غير صالح أو منتهي'], 403);
    reply(['ok' => true, 'expires_at' => $row['expires_at']]);
}
if ($action === 'admin_create' || $action === 'admin_revoke') {
    if ($adminKey === '' || !hash_equals($adminKey, (string)($in['admin_key'] ?? ''))) reply(['ok' => false, 'message' => 'غير مصرح'], 401);
    if ($action === 'admin_create') {
        $days = (int)($in['duration_days'] ?? -1);
        if (!in_array($days, [3, 30, 90, 180, 365, 0], true)) reply(['ok' => false, 'message' => 'مدة غير مدعومة'], 400);
        $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; $raw = '';
        for ($i = 0; $i < 12; $i++) $raw .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        $code = 'HB-' . substr($raw, 0, 4) . '-' . substr($raw, 4, 4) . '-' . substr($raw, 8, 4);
        $q = $db->prepare('INSERT INTO licenses (code_hash, suffix, duration_days, created_at) VALUES (?, ?, ?, ?)');
        $q->execute([code_hash($code), substr($code, -4), $days, time()]);
        reply(['ok' => true, 'code' => $code, 'duration_days' => $days]);
    }
    $code = strtoupper(trim((string)($in['code'] ?? '')));
    $q = $db->prepare('UPDATE licenses SET revoked = 1, token_hash = NULL WHERE code_hash = ?'); $q->execute([code_hash($code)]);
    $changed = $q->rowCount() > 0; reply(['ok' => $changed, 'message' => $changed ? 'تم إيقاف الكود' : 'الكود غير موجود']);
}
reply(['ok' => false, 'message' => 'طلب غير معروف'], 404);
