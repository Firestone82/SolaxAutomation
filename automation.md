# Automatické kontroly

Přehled toho, co jednotlivé moduly dělají a kdy. Všechny hodnoty pocházejí z výchozí konfigurace
v `src/main/resources/application.yml` — pokud jste ji změnili, platí vaše hodnoty, ne ty zde.

Každý modul lze vypnout jeho `enabled` přepínačem, nebo dočasně z nástěnky.

---

## Hlídač nabití baterie — `automation.battery`

Běží každou hodinu v `:05` a jedná jen v nastavených kontrolních bodech.

**Pozadu za plánem** — `self-use-thresholds`, jedná jen v režimu _FEED_IN_PRIORITY_:

| Čas | Požadované nabití | O víkendu |
|-----|-------------------|-----------|
| 13:05 | 50 % | 60 % |
| 14:05 | 70 % | 80 % |
| 15:05 | 90 % | 100 % |

> **POKUD** je baterie pod cílem **a** střídač je v režimu _FEED_IN_PRIORITY_
> **PROVEĎ:** přepni na _SELF_USE_, aby zbytek výroby šel do baterie.

**Napřed před plánem** — `feed-in-thresholds`, jedná jen v režimu _SELF_USE_:

| Čas | Nabití pro přepnutí |
|-----|----------------------|
| 09:05 | 50 % |
| 10:05 | 70 % |

> **POKUD** je baterie na cíli nebo nad ním **a** střídač je v režimu _SELF_USE_
> **a** předpověď na další 3 hodiny má kvalitu **≤ 2,2**
> **PROVEĎ:** přepni na _FEED_IN_PRIORITY_, aby se přebytek prodal místo aby propadl.
>
> **JINAK POKUD** je baterie na cíli, ale obloha je zatažená
> **PROVEĎ:** nech _SELF_USE_ — za slabého slunce výroba sotva pokryje dům a to málo, co by
> odešlo do sítě, se stejně večer nakupuje zpět.

Obě kontroly vychází ze skutečně naměřeného nabití, ne z předpovědi — zachytí i slunečnější
dopoledne, než čekala předpověďová kontrola modulu počasí. Předpověď rozhoduje jen o tom, jestli
se přebytek vůbec vyplatí posílat do sítě (viz níže). Každý kontrolní bod jedná jen ve
"svém" režimu; ostatní režimy (např. _BACKUP_) nechává být.

**Tolerance** — `tolerance`, výchozí **2 %**. Kontrolní bod je očekávání, ne smlouva: baterie na
79 % proti cíli 80 % je prakticky vzato podle plánu a přepínat střídač kvůli takovému rozdílu jen
způsobí, že režim kolem kontrolní hodiny poskakuje. Cíl proto platí za splněný, jakmile je nabití
v rámci tolerance pod ním — v obou směrech, tedy i pro přepnutí do _FEED_IN_PRIORITY_. Víkendový
příplatek se připočte k cíli první, tolerance se měří až proti výslednému číslu.

**Kontrola slunečna** — `feed-in-weather`, platí jen pro přepnutí do _FEED_IN_PRIORITY_. Nabitá
baterie je jen půl důvodu dodávat do sítě; ta druhá půlka je, že ještě něco přijde. Kontrolní bod
proto přepne, jen když průměrná kvalita předpovědi na dalších `look-ahead-hours` (**3 h**) je na
`max-quality` (**2,2**) nebo pod ní — stejná hranice jako `automation.weather.cloudy-threshold`,
aby se oba moduly na tom, co je „slunečno“, shodly. Když předpověď není dostupná, kontrola se
přeskočí a režim zůstane beze změny. Bez API klíče na počasí nastavte `enabled: false` — modul se
pak jako dřív rozhoduje jen podle nabití. Kontroly `self-use-thresholds` počasí neřeší vůbec:
přestat dodávat je vždy bezpečné.

---

## Limit dodávky — `automation.export`

Běží **každou čtvrthodinu** v `:00`, `:15`, `:30` a `:45` mezi 4:00 a 20:59, a navíc při každé
změně přepínače připojení. Přesně na čtvrthodinu proto, že přesně tehdy se mění i cena — a je známá
dlouho dopředu, takže není na co čekat. Hodinová kontrola by nechala střídač dodávat do záporné
ceny až tři čtvrtě hodiny.

> **POKUD** je spotová cena **≥ 0,5 CZK/kWh**
> **PROVEĎ:** otevři dodávku na 3 950 W.
>
> **JINAK POKUD** je přepínač v poloze _HIGH_ (připojeno na měřenou síť)
> **PROVEĎ:** uzavři dodávku na 20 W — prodej by se nevyplatil, při záporné ceně by dokonce stál peníze.
>
> **JINAK** <small>(přepínač _LOW_, dodávka se k měřené přípojce nedostane)</small>
> **PROVEĎ:** otevři dodávku na 3 950 W.

V grafu spotové ceny jsou intervaly pod **0,5 CZK/kWh** uvnitř aktivních hodin přeškrtnuté šrafou a
jejich sloupce ustoupí do pozadí — tam se dodávka do měřené sítě nevyplatí a modul limit uzavře.
Mimo aktivní hodiny se limitem nehýbe, takže se ani nešrafuje.

Polední přiškrcení, jen při přepínači _LOW_:

> **POKUD** je 12:00–14:59 **a** průměrná kvalita předpovědi je **≤ 3,0**
> **PROVEĎ:** nastav dodávku na `power.reduced`.

Ve výchozím nastavení je `power.reduced` rovna `power.maximum` (3 950 W), takže přiškrcení nic
nemění. Snižte ji, pokud chcete v zatažené poledne nechat víc výroby v baterii.

---

## Režim podle počasí — `automation.weather`

Běží každou hodinu v `:02`. V nastavených kontrolních bodech řeší slunečno/zataženo, ve všech
ostatních hodinách kontroluje bouřku.

**Kvalita předpovědi** = úroveň typu počasí + oblačnost / 100. Nižší je slunečnější: 0 je jasno,
3–5 zataženo nebo déšť, 10 a výš bouřka.

### Kontroly výhledu výroby

| Čas | Sledované okno | Nabití potřebné pro _FEED_IN_PRIORITY_ |
|-----|----------------|-----------------------------------------|
| 07:02 | 09:00–12:59 | 10 % |
| 09:02 | 10:00–14:59 | 20 % |
| 11:02 | 12:00–16:59 | 40 % |

> **POKUD** je kvalita **≤ 2,2** (slunečno) **a** baterie má dost
> **PROVEĎ:** nastav _FEED_IN_PRIORITY_ — přebytek se vyplatí dodávat.
>
> **JINAK POKUD** je kvalita **> 2,2** (zataženo)
> **PROVEĎ:** nastav _SELF_USE_ — výroba patří do baterie.

Pozdější kontroly chtějí plnější baterii, protože zbývá méně dne na nápravu špatného odhadu.

### Kontrola bouřky

Každou ostatní hodinu, výhled na následující 2 h.

> **POKUD** je kvalita **> 10,0**
> **PROVEĎ:** nastav _BACKUP_ a drž baterii jako zálohu pro výpadek.
>
> **JINAK POKUD** je střídač v _BACKUP_ **a** příští hodina klesla pod **8,5**
> **PROVEĎ:** vrať _SELF_USE_.

Hystereze 1,5 brání překlápění režimu, když nad domem přechází fronta. Režim _BACKUP_ nastavený
ručně modul nikdy nesahá — opustí ho jen tehdy, když ho sám zapnul.

---

## Prodej do sítě — `automation.discharge`

Plánuje se jednou denně v **15:00**, tedy až po zveřejnění denního trhu.

Trh se vypořádává po **15 minutách**, den má tedy 96 cen. Plánovač postupuje ve třech krocích:

1. **Špička** — nejdražší interval mezi 15:00 a 23:45.
2. **Plató** — od špičky se rozšiřuje na obě strany, dokud sousední intervaly zůstávají do
   **1 CZK/kWh** pod ní. To je ta část večera, do které se opravdu vyplatí prodávat.
3. **Umístění** — baterie zřídka pokryje celé plató, takže se okno dlouhé přesně na to, co baterie
   utáhne, posouvá po platu a vybere se umístění s nejvyšším výnosem. **Při shodě vyhrává pozdější**
   umístění, aby vybíjení skončilo na konci platu, a ne aby došlo ještě před špičkou.

> **POKUD** je špička **< 8 CZK/kWh**
> **PROVEĎ:** neplánuj nic a napiš do logu proč.

**Plánovač se na baterii nedívá.** Běží v 15:00, tedy hodiny před večerní špičkou a ještě za
slunce — nabití změřené v tu chvíli neříká nic o nabití v 19:00. Okno se proto plánuje jen podle
ceny a délku určuje `(min-battery − 40 % rezerva) × 11,6 kWh × 0,92 ÷ 4 600 W` (vybíjecí výkon),
nejvýše 4 hodiny.
`min-battery` je 50 % — nabití, které prodej stejně vyžaduje; když už je baterie výš, počítá se
to skutečné. Nadhodnotit se nedá, protože stejná hodnota je i práh pro zahájení; podhodnotit ne,
okno by bylo příliš krátké na využití špičky.

Jestli se prodej vůbec vyplatí zahájit, se rozhoduje **znovu při otevření okna**, proti té samé
hodnotě:

> **POKUD** je při startu baterie **< 50 %** (`min-battery`)
> **PROVEĎ:** okno zahoď a napiš do logu proč.

Okno naplánované ručně z nástěnky tuhle hranici ignoruje — je to rozhodnutí člověka —, rezervu
ale nikdy.

**Vybíjecí výkon `discharge-power` je výkon baterie, ne výkon do sítě.** Dálkové řízení řídí baterii
a dům se z ní krmí dřív, než cokoli dorazí k elektroměru — při nastavení přesně na
`automation.export.power.maximum` (3 950 W) tedy do sítě odejde limit *mínus* aktuální spotřeba domu
a zbytek se „prodá“ do vlastní rychlovarné konvice, a to zrovna v nejdražší hodinu dne.

> **POKUD** má do sítě odcházet celý limit
> **PROVEĎ:** nastav `discharge-power` **nad** limit — rozdíl pokryje spotřebu domu a limit dodávky
> ve střídači drží elektroměr na 3 950 W. Výchozí **4 600 W** je 3 950 W + 650 W rezervy na spotřebu.

Platí dva stropy: co baterie a střídač zvládnou dodat (o víc se dá požádat, ale nevznikne to) a limit
dodávky, který je po dobu prodeje jediné, co drží přebytek mimo elektroměr — rezervu proto nastavujte
podle skutečné spotřeby domu, ne podle štítkového výkonu střídače. Hodnota `0` znamená „řiď se
limitem dodávky“, tedy chování před zavedením tohoto nastavení.

Obě čísla se používají jinde a log je uvádí obě: délka okna se počítá z **vybíjecího** výkonu, protože
tou rychlostí se baterie doopravdy vyprazdňuje, kdežto očekávaný výnos jen z té části, která projde
elektroměrem — co sní dům, nikdo nekoupí.

Prodej se provádí **dálkovým řízením**, ne změnou režimu střídače: relace má vlastní dobu trvání a
střídač se po ní sám vrátí do svého režimu — i kdyby tahle aplikace mezitím spadla. Hlídač kontroluje
baterii každé 2 minuty a ukončí relaci dřív, jakmile se dosáhne rezervy 40 %.

V grafu spotové ceny je ta část dne, do které se **smí** prodávat — uvnitř prohledávaných hodin
(15:00–23:45) a na minimální ceně (8 CZK/kWh) nebo nad ní — podbarvená barvou prodeje a minimální
cena je vedená čárkovaně napříč grafem. Je to místo, kde okno může vzniknout, ne kde vznikne:
jestli v něm nakonec vznikne, rozhoduje až plánovač podle špičky, plató a nabití baterie.

Okno lze z nástěnky zrušit (**Zrušit plán**), nahradit jiným (**Přeplánovat**) nebo naplánovat
ručně (**Naplánovat okno**). Ruční okno jde zadat dvěma způsoby: **mezi časy**, nebo **začít hned**
na zvolenou dobu. Varianta „začít hned“ se řídí hodinami aplikace, ne prohlížeče.

---

## Rychlé akce z nástěnky

Vedle karty prodeje sedí **Rychlé akce** — ruční zásahy pro to, o čem automatika vědět nemůže.
Nic se neplánuje ani nepamatuje:

- **Režim střídače** (_Vlastní spotřeba_, _Priorita dodávky_, _Záloha_) — trvalý. Přežije restart
  a modul jej může při dalším běhu opět změnit. Aktuální režim je na tlačítkách zvýrazněný a přepne
  se hned po přijetí příkazu, ne až s dalším čtením střídače; změna si navíc zapíše i režim, ze
  kterého se přepínalo, takže pruh _Režim střídače dnes_ má čím obarvit úsek před ní.
- **Dálkové řízení** (_Nabít ze sítě_, _Prodat do sítě_, _Ukončit dálkové řízení_) — střídač se sám
  vrátí do svého režimu, i kdyby tahle aplikace spadla. Vyžaduje připojení k SolaX Cloud.
  _Ukončit dálkové řízení_ je aktivní jen tehdy, když nějaká relace opravdu běží; řádek pod kartou
  napíše, proč je případně vypnuté.

Nabíjet lze dvěma způsoby: **na dobu**, nebo **do nabití**. Vyplňte _Do nabití_ a relace poběží,
dokud baterie nedosáhne zadané hodnoty, ať to trvá jakkoli dlouho — konec určí sám střídač, tenhle
režim (`soc_target_control_mode`) žádný časovač nemá. Pole s dobou se přitom ztlumí a řádek pod
poli předem napíše, která z obou variant se odešle.

Ukončení dálkového řízení během běžícího prodeje projde modulem prodeje, ne za jeho zády, takže
naplánované okno i historie zůstanou v souladu se střídačem.

**Opuštění dálkového řízení se posílá dvěma příkazy, ne jedním.** Cloud hlásí `exit_vpp_mode` jako
úspěšné, jakmile ho zařadí do fronty, a některé střídače relaci sice ukončí, ale zůstanou ve svém
stavu dálkového řízení — v aplikaci SolaX _Normal Mode(R-n)_ —, dokud opravdu neproběhne
dokumentovaný přechod *exit remote control*. Nejdřív se proto pošle jednosekundová relace s nulovým
výkonem a `nextMotion = 160`, která tenhle přechod provede, a přímé ukončení jde až na ni. Jedna
sekunda při 0 W s baterií neudělá nic. Pokud váš střídač odchází i po samotném `exit_vpp_mode`,
nastavte `solax.cloud.exit-with-push-power: false`.

---

## Přepínač přípojky

Stav GPIO vstupu (BCM 17) je vidět v dlaždicích na přehledu: _Měřená síť_ při HIGH, _Druhá přípojka_
při LOW. Mimo Raspberry Pi, nebo s `raspberry.enabled: false`, se čte záskok hlásící trvale HIGH —
dlaždice to napíše, aby se náhražka nepletla s měřením.

Každé přehození přepínače se navíc zapisuje do historie, takže průběh dne se kreslí jako tenký pruh
pod pruhem režimu střídače (graf _Režim střídače dnes_) — obojí na jedné časové ose, protože co
dělal limit dodávky odpoledne dává smysl až vedle toho, do které přípojky se v tu chvíli dodávalo.
Se záskokem místo skutečného GPIO se pruh nekreslí vůbec: celý den „měřené sítě“, který nikdo
nezměřil, by byl výmysl.

---

## Změny režimu mimo aplikaci — `solax.control.work-mode-watch`

Střídač nepřepíná jen tato aplikace: režim jde změnit z aplikace SolaX, přímo na displeji střídače
i plánem uloženým v něm — a nic z toho se sem neohlásí. Pracovní režim se proto **jednou za minutu**
(`interval`) čte zpátky a změna, kterou aplikace neudělala, se zapíše do historie jako každá jiná.

> **POKUD** střídač hlásí jiný režim, než jaký měl při minulém čtení,
> **a** není to režim, který aplikace sama krátce předtím zapsala
> **PROVEĎ:** zapiš změnu do historie pod zdroj _Střídač_ — objeví se v _Poslední aktivitě_
> i v pruhu _Režim střídače dnes_.

Nic navíc se kvůli tomu nečte: kontrola se dívá na tentýž uložený snímek, který stejně obsluhuje
nástěnku i moduly.

**Vlastní zápisy se nezapisují dvakrát.** Každé úspěšné nastavení režimu se hlídači ohlásí a režim,
který odpovídá zápisu mladšímu než `attribution-window` (**5 min**, aby přežil i frontu příkazu přes
cloud), se bere jako vlastní — zaznamenal ho už ten, kdo ho nastavil.

**Co hlídač nevidí:** změnu provedenou, když aplikace neběžela nebo byl střídač nedostupný — pod
grafem se pak jen napíše, že se aktuální režim neshoduje s poslední zaznamenanou změnou — a cokoli
během dálkového řízení, které střídač řídí bez sáhnutí na trvalý režim.

**Nic se podle toho neděje.** Jde o záznam, ne o zásah: ručně nastavený režim vydrží, dokud
nepřijde další kontrolní bod modulu počasí nebo baterie a nerozhodne jinak.

---

## Historie akcí

Zaznamenané akce se drží **48 hodin** (`timeline.retention`) a ukládají se do `data/timeline.json`,
takže po restartu nástěnka nezačíná prázdná a grafy nemají díry, které už nic nedoplní. Strop
`timeline.persisted-events` je jen pojistka, aby zacyklený modul soubor nenafoukl. Záznamy starší než
okno se při čtení souboru zahodí — aplikace vypnutá týden se nesmí vrátit s týden starou „poslední
aktivitou“. Je to pořád jen pohodlí pro zobrazení — trvalým záznamem zůstávají rotující logy v `logs/`.

Každý řádek má nadpis a vysvětlení: co modul rozhodl a proč. Kolik řádků je vidět, se přepíná
přímo v hlavičce karty _Poslední aktivita_ (5 až 100) a volba se pamatuje v prohlížeči.

V hlavičce jsou i dva filtry, obojí zapamatované v prohlížeči: **Dnes / Včera / Vše** vybírá den
(historie sahá dva dny zpět) a **Jen změny** schová kontroly, které nic neudělaly — limit dodávky se
přepočítává každou čtvrthodinu a skoro vždy vyjde „není co dělat“, což je 68 řádků denně, pod kterými
se ztratí těch pár, které něco říkají. **Neúspěšná** kontrola zůstane vidět vždycky.

Tytéž záznamy kreslí i graf **Plánovaných akcí**, když se přepne na _Celý den_: hodiny před
aktuálním časem se doplní tím, co doopravdy proběhlo, na řádku svého modulu a potlačeně, aby plán
zůstal to první, co je vidět. Neúspěšný běh je červený. Graf **Kvality počasí** se přepíná stejně;
hodiny, které už proběhly, se ukládají průběžně do `data/weather-history.json`, protože předpověď
sama dozadu nevidí. Restart je tedy nesmaže, ale co aplikace nikdy neviděla (třeba ráno před prvním
spuštěním), v grafu chybí — osa i tak zůstane celý den a pod grafem se napíše, odkdy jsou hodiny
zaznamenané.
