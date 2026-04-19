# BAKAPI

Desktop aplikace v Javě (Swing), která se přihlásí do Bakalářů a zobrazí průběžné známky v tabulce včetně učitele.

Učitelé se načítají z dalších zdrojů Bakalářů:
- stránka předmětů (`/next/predmety.aspx`, případně `/next/subjects.aspx`)
- fallback přes API (`/api/login` + `/api/3/subjects`)

UI podporuje **světlý/tmavý motiv**:
- výchozí motiv se nastaví podle motivu systému,
- v aplikaci jde kdykoli přepnout tlačítkem `Světlý` / `Tmavý`.

Po spuštění se nejdřív zobrazí jen přihlašovací obrazovka. Přehled známek se otevře až po úspěšném přihlášení.

V přehledu známek je:
- filtrovatelný každý sloupec (textové filtry),
- u každé známky spočtený příspěvek do váženého průměru předmětu,
- souhrn po předmětech: vážený průměr (2 desetinná místa), výsledná známka a slovní hodnocení,
- celkový průměr výsledných známek napříč předměty.

## Požadavky

- Java 21+
- Maven 3.8+

## Spuštění

```bash
mvn clean compile exec:java
```

Volitelně je možné použít proměnné prostředí:

- `BAKA_BASE_URL` (výchozí: `https://bakalari.infis.cz`)
- `BAKA_USER`
- `BAKA_PASS`

Tyto hodnoty se předvyplní do GUI formuláře.
