// Time rendering, in one place.
//
// Every clock face in the product is Africa/Tunis: the API stores and sends
// UTC (invariant 6), and the passenger reads network time, never their own
// device's zone. Formatters are module-level because `Intl.DateTimeFormat` is
// expensive to construct and these run on every SSE delta.

const ZONE_RESEAU = "Africa/Tunis";

const formateurHeure = new Intl.DateTimeFormat("fr-FR", {
  timeZone: ZONE_RESEAU,
  hour: "2-digit",
  minute: "2-digit",
});

/** `HH:MM` in network time. */
export function formaterHeure(iso: string): string {
  return formateurHeure.format(new Date(iso));
}

const formateurHorloge = new Intl.DateTimeFormat("fr-FR", {
  timeZone: ZONE_RESEAU,
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

/** `HH:MM:SS`, for the station board's live clock. */
export function formaterHorloge(date: Date): string {
  return formateurHorloge.format(date);
}

const formateurDateHeure = new Intl.DateTimeFormat("fr-FR", {
  timeZone: ZONE_RESEAU,
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

/** `DD/MM/YYYY HH:MM` in network time -- the incidents console spans several
 * days, so a bare `HH:MM` would be ambiguous about which day. */
export function formaterDateHeure(iso: string): string {
  return formateurDateHeure.format(new Date(iso));
}

/**
 * Africa/Tunis has had no fixed daylight-saving shift since 2005 (see the
 * dashboard's date-range comment), so its UTC offset is the constant below
 * year-round. Used to turn a `datetime-local` input -- a naive wall-clock
 * string with no timezone of its own -- into the UTC instant the API expects,
 * without ever trusting the browser's own timezone (invariant 6).
 */
const DECALAGE_TUNIS = "+01:00";

/** `"YYYY-MM-DDTHH:mm"` (an `<input type="datetime-local">` value), read as
 * Africa/Tunis wall-clock time, to a UTC ISO-8601 instant. */
export function versIsoDepuisLocalTunis(valeurInputLocal: string): string {
  return new Date(`${valeurInputLocal}:00${DECALAGE_TUNIS}`).toISOString();
}

/** Today's Africa/Tunis instant, formatted for a `datetime-local` input's
 * default value -- never `new Date().toISOString().slice(...)`, which is UTC. */
export function maintenantPourInputLocalTunis(): string {
  const parties = new Intl.DateTimeFormat("en-CA", {
    timeZone: ZONE_RESEAU,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date());
  const valeur = (type: string) => parties.find((p) => p.type === type)?.value ?? "00";
  return `${valeur("year")}-${valeur("month")}-${valeur("day")}T${valeur("hour")}:${valeur("minute")}`;
}
