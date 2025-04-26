# Automatické kontroly:
## Nabití baterie:
- v 13:00
    > - **POKUD:** Baterie je pod **50%** při režimu _FEED_IN_PRIORITY_.
    > - **PROVEĎ:** Nastav režim na _SELF_USE_.
- v 15:00
    > - **POKUD:** Baterie je pod **70%** při režimu _FEED_IN_PRIORITY_.
    > - **PROVEĎ:** Nastav režim na _SELF_USE_.

## Negativní export:
- Každou hodinu mezi 4:00 - 20:00
    > - **POKUD:** Pokud je přepínač v poloze _LOW_ (připojeno na Wujka).
    > - **PROVEĎ:** Nastav export na 3,950 W.
    > - **JINAK:** <small>(Přepinač v poloze _HIGH_ (připojeno na ČEZ))</small>
    >  >  - **POKUD:** Pokud je prodejní cena pod **0.5 CZK/kWh**.
    >  >  - **PROVEĎ:** Nastav export na 0 W.
    >  >  - **JINAK:** Nastav export na 3,950 W.

## Počasí:
- v 6:00  
    > - **POKUD:** Počasí mezi  7-12h je **dostatečně** slunečné.
    > - **PROVEĎ:** Nastav režim na _FEED_IN_PRIORITY_.
    > - **JINAK:** Nastav režim na _SELF_USE_.
- v 11:00h
    >  - **POKUD:** Pokud počasí mezi 12-18h je **hodně** slunečné.
    >  - **PROVEĎ:** Nastav režim na _FEED_IN_PRIORITY_
    >  - **JINAK:** Nastav režim na _SELF_USE_.
- Každou hodinu mezi 8:00 - 2:00
    > - **POKUD:** Pokud počasí v **dalších 3h** je dostatečně detekováno jako bouřka.
    > - **PROVEĎ:** Nastav režim na _BACKUP_.
    > - **JINAK:**
    >  >  - **POKUD:** Je režim nastaven na _BACKUP_.
    >  >  - **PROVEĎ:** Nastav režim na _SELF_USE_.
