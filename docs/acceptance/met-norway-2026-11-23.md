# MET Norway API: acceptance of terms

- **Service:** MET Norway Locationforecast (api.met.no)
- **Terms:** https://docs.api.met.no/doc/TermsOfService and https://docs.api.met.no/doc/FAQ
- **Accepted by:** the project owner, Kosta Vukićević
- **Date accepted:** 2026-11-23
- **Recorded by:** an AI coding agent, from the owner's project brief, which states the acceptance and its date.

## Rules this project follows as a condition of use

1. **Foreground only.** The app never sends a request to MET Norway unless it is open in the foreground. There are no background requests to this provider, ever. Background work uses Open-Meteo (one refresh per day per install) or local recomputation only.
2. Every request sends `User-Agent: Overgrown/<version> https://github.com/stcksmsh/overgrown`.
3. A request is never repeated before the `Expires` time of the cached response. Requests send `If-Modified-Since`. Every response is cached.
4. Coordinates are sent with at most 4 decimals; the app uses 2.
5. Traffic stays far below 20 requests per second across all installs. The client backs off on HTTP 429. HTTP 403 is treated as a bug to fix.

These rules are enforced in the `:weather` module and covered by its tests.
