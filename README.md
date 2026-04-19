# BAKAPI

Desktop aplikace v Javě (Swing), která se přihlásí do Bakalářů a zobrazí průběžné známky v tabulce včetně učitele.

Učitelé se načítají z dalších zdrojů Bakalářů:
- stránka předmětů (`/next/predmety.aspx`, případně `/next/subjects.aspx`)
- fallback přes API (`/api/login` + `/api/3/subjects`)

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
