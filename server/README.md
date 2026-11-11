# HB Browser License API

This backend binds each license to the first Android device that activates it. Subscription time starts on first activation. Supported terms: 3, 30, 90, 180, 365 days, or 0 (perpetual).

## Hosting requirements
- PHP 8.1 or newer
- PDO_SQLite enabled
- HTTPS enabled
- Writable `server/data` directory
- Set a strong server environment variable named `HB_ADMIN_KEY` (at least 32 random characters). Never put it in the Android app or public repository.

Upload the contents of this folder to a private HTTPS path such as `https://YOUR-DOMAIN/hb-license/`. The database is created automatically in `data/licenses.sqlite`. Keep the data directory blocked from public access.

## Admin
Open `admin.html`, enter the server-side HB_ADMIN_KEY, and create or revoke keys. A generated code is shown only once. Do not share the admin key with customers.

## Android configuration
In `app/build.gradle.kts`, replace the placeholder in `HB_LICENSE_API` with the full HTTPS URL ending in `api.php`.

The Android app checks the server on first activation and approximately every 24 hours. A previously validated subscription has a maximum 72-hour offline grace period. Server-side expiry and revocation are authoritative.

## Important
This is a first implementation, not a hardened commercial licensing service. Use HTTPS, keep the admin key secret, back up the SQLite database, and test on a spare device before selling subscriptions. Android ID binding discourages casual sharing but cannot guarantee resistance to rooted-device spoofing.
