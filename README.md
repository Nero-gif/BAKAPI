# BAKAPI

Desktop aplikace v Javě (Swing), která se přihlásí do Bakalářů a zobrazí průběžné známky v tabulce včetně učitele.

Učitelé se načítají z dalších zdrojů Bakalářů:
- stránka předmětů (`/next/predmety.aspx`, případně `/next/subjects.aspx`)
- fallback přes API (`/api/login` + `/api/3/subjects`)

UI podporuje **světlý/tmavý motiv**:
- výchozí motiv se nastaví podle motivu systému,
- v aplikaci jde kdykoli přepnout tlačítkem `Světlý` / `Tmavý`.

Po spuštění se nejdřív zobrazí jen přihlašovací obrazovka. Přehled známek se otevře až po úspěšném přihlášení.

## Lokální profily a offline režim

- Aplikace si pamatuje přihlášené profily (URL + uživatel) a nabízí je v rozbalovacím seznamu uživatele.
- Po úspěšném online načtení se známky uloží do lokálního šifrovaného cache.
- Při dalším online načtení se cache aktualizuje po jednotlivých známkách (nezměněné záznamy se zachovají).
- Lokální metadata známek (např. stav plánu doplnění) se při online synchronizaci zachovávají.
- Při výpadku internetu se známky načtou z cache jen po zadání správného hesla k danému účtu.
- Heslo se nikam neukládá v plaintextu; ukládá se pouze hash hesla a šifrovaná data.

V přehledu známek je:
- filtrovatelný každý sloupec (textové filtry),
- u každé známky spočtený příspěvek do váženého průměru předmětu,
- souhrn po předmětech: vážený průměr (2 desetinná místa), výsledná známka a slovní hodnocení,
- celkový průměr výsledných známek napříč předměty.

Tabulka známek a tabulka průměrů jsou oddělené do záložek (`Známky` / `Průměry předmětů`).

Další statistiky:
- záložka `Statistika známek`: počty jednotlivých známek (1–5) pro každý předmět,
- v záložce `Průměry předmětů`: rozložení výsledných známek (kolik předmětů vychází na 1, 2, 3, 4, 5).
- záložka `Známky k doplnění`: zobrazuje známky `N`, `A`, `4`, `5` a umožní uložit stav plánu (`Nerealizovatelné`, `Plánováno`, `Neplánováno`, `K zvážení`).
- tlačítko `Nahrát konzultace (PDF)`: načte PDF s konzultačními hodinami vyučujících, automaticky je spáruje s učiteli u známek a zobrazí je v záložce `Známky k doplnění`.
  Načtená data se uloží k profilu a při dalším přihlášení se načtou automaticky; nahrání nového PDF původní uložené konzultace přepíše.

Tabulka statistiky známek používá dynamické sloupce podle reálně nalezených známek (např. `N`). Známky typu `1-` se započítávají do sloupce `1`.

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
