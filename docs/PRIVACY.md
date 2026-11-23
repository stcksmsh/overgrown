# Privacy policy

**Draft, pending the owner's review before any release.**

Overgrown is published by Kosta Vukićević. Questions: open an issue at https://github.com/stcksmsh/overgrown/issues.

## What Overgrown does not do

- No account. No sign-in.
- No analytics, no crash reporting, no advertising, no tracking.
- No server run by the developer. Nothing you enter is sent to the developer.

## What stays on your phone

Your places, plants, pots, watering and soil check-ins, pot weights (if you use calibration) and the weather history the app keeps. All of it is stored only on your phone.

If Android's Auto Backup is on for your phone, Android backs this data up to your own Google account, as it does for other apps. Google, not the developer, handles that backup. You can turn it off in Android's settings.

## What leaves your phone

To get the weather, the app asks a weather provider directly from your phone:

- **Open-Meteo** (https://open-meteo.com/) by default, once a day in the background and when you open the app;
- **MET Norway** (https://api.met.no/) only if Open-Meteo is failing, and only while the app is open.

The provider receives your phone's IP address and the location of each place, rounded to two decimals (about 1 km). When you search for a city, the name you type goes to Open-Meteo's place search. Nothing else is sent: no plant data, no identifiers.

The "Report wrong data" button opens GitHub in your browser with a prefilled issue. Nothing is sent unless you submit it there.

## Permissions

- **Internet**: to fetch the weather.
- **Notifications**: for watering reminders and weather alerts. You can refuse; the app still works when you open it.

## Your data: export and deletion

- **Export**: Settings, "Export all data" writes one JSON file where you choose.
- **Delete**: delete plants and places in the app, or clear the app's storage or uninstall it in Android's settings. That removes everything from the phone. Remove the Auto Backup copy from your Google account settings if you want it gone too.
