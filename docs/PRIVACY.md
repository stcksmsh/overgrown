# Privacy policy

**Draft, pending the owner's review before any release.**

Overgrown is published by Kosta Vukićević. Questions: open an issue at https://github.com/stcksmsh/overgrown/issues.

## What Overgrown does not do

- No account. No sign-in.
- No analytics, no crash reporting, no advertising, no tracking.
- No server run by the developer. Nothing you enter is sent to the developer.

## What stays on your phone

Your places, plants, pots, watering and soil check-ins, pot weights (if you use calibration), the weather history the app keeps and your plant photos. All of it is stored only on your phone.

**Photos.** A photo you take with the camera app or choose from the gallery is copied into the app's own storage, with its date. Photos are not uploaded and nothing analyses them. They are excluded from Android's Auto Backup and from device-to-device transfer, so a photo leaves your phone only in an export you start (see below). The app asks for no camera or storage permission: the camera app or Android's photo picker does the taking or choosing.

If Android's Auto Backup is on for your phone, Android backs up the rest of this data (everything except the photos and the app's bundled care data) to your own Google account, as it does for other apps. Google, not the developer, handles that backup. You can turn it off in Android's settings.

## What leaves your phone

To get the weather, the app asks a weather provider directly from your phone:

- **Open-Meteo** (https://open-meteo.com/) by default, once a day in the background and when you open the app;
- **MET Norway** (https://api.met.no/) only if Open-Meteo is failing, and only while the app is open.

The provider receives your phone's IP address and the location of each place, rounded to two decimals (about 1 km). When you search for a city, the name you type goes to Open-Meteo's place search. Nothing else is sent: no plant data, no identifiers.

**"Use my location".** When you tap this button in the add-plant form, the app asks for approximate location only (`ACCESS_COARSE_LOCATION`). It reads one fix from Android's own location service, without Google Play Services, and no GPS or background location. The fix is rounded to two decimals of a degree (about 1 km) before anything is kept. The rounded position is matched on the phone against a bundled city list to suggest a place name, and then saved as the place's location, like a place you typed. The raw fix is never stored and is not sent anywhere; the rounded position leaves the phone only as the place's location in the weather request above. The app does not read your location unless you tap the button.

**City list.** The names offered for "Use my location" come from a list bundled in the app (Natural Earth populated places, public domain). It is read on the phone, offline.

The "Report wrong data" button opens GitHub in your browser with a prefilled issue. Nothing is sent unless you submit it there.

## Permissions

- **Internet**: to fetch the weather.
- **Notifications**: for watering reminders and weather alerts. You can refuse; the app still works when you open it.
- **Approximate location**: only if you tap "Use my location", and only for that tap. You can refuse; searching for a city or typing coordinates still works.

## Your data: export and deletion

- **Export**: Settings, "Export all data and photos (zip)" writes one zip file where you choose: your data as JSON and your photos. A separate "Export calibration (CSV)" writes your pot weights. Nothing is exported unless you start it. Import reads such a zip, or the plain JSON file of older versions.
- **Delete**: delete plants and places in the app, or clear the app's storage or uninstall it in Android's settings. That removes everything from the phone. Remove the Auto Backup copy from your Google account settings if you want it gone too. Exports you made are files you own; delete them yourself.
