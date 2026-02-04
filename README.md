# Sparkel [![Build](https://github.com/navikt/helse-sparkelapper/actions/workflows/build.yml/badge.svg)](https://github.com/navikt/helse-sparkelapper/actions/workflows/build.yml)

Mikrotjenester som svarer ut behov ved å hente data fra ulike registre.

Dette er for øvrig et skikkelig bra sted å skrive navnene på hver tjeneste og en one-liner om hva de er ment å gjøre. Og så kan man bruke README for hver tjeneste for å beskrive nøyere.

## Legge til en ny gradle-modul

1. Lag en mappe og sørg for at det finnes en `build.gradle.kts` der
2. Lag App.kt i `src/main/kotlin/no/nav/helse/sparkel/[app]`

## Legge til ny app

Alle gradle-modulene bygges og releases automatisk.
Ved hver pakke som blir lastet opp trigges en deployment workflow for den pakken.

Navnet på appen blir prefikset med `sparkel-` i nais.yml, så navnet på modulen skal være uten.

1. Gjør 'Legge til en ny gradle-modul'. Mappenavnet korresponderer med appnavnet
2. Lag `config/[app]/[cluster].yml` for de klustrene appen skal deployes til.
3. Push endringene

## Oppgradering av gradle wrapper

Finn nyeste versjon av gradle her: https://gradle.org/releases/

Kjør denne kommandoen for å oppdatere `gradle-wrapper.properties`:

`./gradlew wrapper --gradle-version <versjon>`

Kjør samme kommando en gang til for å laste ned eventuelt oppdaterte jar- og skript-filer.

Oppdater gradle-versjonen i build.gradle.kts-filen:

`val gradlewVersion = "<versjon>"`

## Hvorfor Sparkel?

Det ble tidlig klart at sykepenge-tjenesten ville måtte hente data fra en del forskjellige tjenester utenfor teamet (og etter hvert som Produktområde Helse ble grunnlagt, uten POet). Det ble også klart at det var svært mange forskjellige måter å hente denne dataen på. Noen tjenester tilbød http-apier, noen brukte GraphQL, noen brukte ActiveMQ, osv. Vi ønsket å slippe at våre kjerneapplikasjoner skulle måtte forholde seg til alle disse forskjellige protokollene, så vi laget et sett med mikrotjenester, der hver tjeneste skulle integrere mot én ekstern tjeneste hver. Dermed fikk hver mikrotjeneste én protokoll å forholde seg til, og kjerneapplikasjonene kunne bruke samme måte å hente data på uavhengig av kilde.

Vi tenkte at en slik harmonisering av grensesnitt fungerte som et lag sparkel over en ujevn vegg, så da var fellesnavnet for disse applikasjonene selvsagt: Sparkel.

## Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

### For NAV-ansatte

Interne henvendelser kan sendes via Slack i kanalen [#team-bømlo-værsågod](https://nav-it.slack.com/archives/C019637N90X)
