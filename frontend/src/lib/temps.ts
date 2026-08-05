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
