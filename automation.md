# Automatické kontroly

Přehled toho, co jednotlivé moduly dělají a kdy. Všechny hodnoty pocházejí z výchozí konfigurace
v `src/main/resources/application.yml` — pokud jste ji změnili, platí vaše hodnoty, ne ty zde.

Každý modul lze vypnout jeho `enabled` přepínačem (dočasně do restartu i přes
`POST /api/modules/{id}/enabled?value=false`).

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

**Vybíjecí výkon `discharge-power` není výkon do sítě.** Dům se z prodeje krmí dřív, než cokoli
dorazí k elektroměru — při nastavení přesně na
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

**Nejdřív panely (`pv-first`, zapnuto).** Relace dálkového řízení drží **baterii** na pevném výkonu.
Když baterie sama zaplní limit dodávky i spotřebu domu, výroba z panelů nemá kam odejít a střídač ji
po celou dobu prodeje omezí na nulu — večerní slunce přijde nazmar a baterie se vybíjí naplno.
Proto se baterie žádá jen o to, co panely nepokryjí: `discharge-power` minus aktuální výroba FVE.
Dohromady dávají pořád `discharge-power`, takže elektroměr vidí celý limit, jen podíl panelů zůstane
v baterii. Hlídač při každé kontrole výrobu znovu přečte a podíl baterie pošle znovu, jakmile se má
změnit o víc než `pv-follow-deadband` (150 W) — jak slunce zapadá, přebírá prodej baterie.

> **POKUD** se má prodávat jen z baterie jako dřív
> **PROVEĎ:** nastav `automation.discharge.pv-first: false`.

V grafu spotové ceny je ta část dne, do které se **smí** prodávat — uvnitř prohledávaných hodin
(15:00–23:45) a na minimální ceně (8 CZK/kWh) nebo nad ní — podbarvená barvou prodeje a minimální
cena je vedená čárkovaně napříč grafem. Je to místo, kde okno může vzniknout, ne kde vznikne:
jestli v něm nakonec vznikne, rozhoduje až plánovač podle špičky, plató a nabití baterie.

Okno lze z nástěnky zrušit (**Zrušit plán**), nahradit jiným (**Přeplánovat**) nebo naplánovat
ručně (**Naplánovat okno**). Ruční okno jde zadat dvěma způsoby: **mezi časy**, nebo **začít hned**
na zvolenou dobu. Varianta „začít hned“ se řídí hodinami aplikace, ne prohlížeče.

---

## Ranní nákup a prodej — `automation.morning`

Ve výchozím stavu **vypnuto** (`enabled: false`), protože nakupuje ze sítě. Před zapnutím nastavte
cenu X (`sell.min-price`) podle svého nočního proudu.

Dům má druhou přípojku s levným nočním proudem od půlnoci do 6:00. Ve **2:00** se modul rozhodne,
jestli se vyplatí baterii z nočního proudu nabít. Stačí jeden ze dvou důvodů:

> **POKUD** má předpověď na 8:00–16:00 průměrnou kvalitu **3,0 nebo horší** (zhruba skoro zataženo)
> **PROVEĎ:** nabij baterii z nočního proudu, aby dům přes den utáhla.

> **POKUD** ranní cena mezi 6:15 a 9:00 někdy dosáhne **X CZK/kWh**
> **PROVEĎ:** nabij baterii z nočního proudu, aby se od 6:15 mohla prodat.

Nenabíjí se, když je baterie už na 100 % nebo když je dům na měřené síti, kde noční proud neplatí.
Nabíjí se 3 000 W dálkovým řízením, které samo skončí v 6:00. Každou minutu se kontroluje, a když je
baterie plná nebo dům přejde na měřenou síť, nabíjení skončí dřív. Restart ve 3:30 noc neztratí,
rozhodne se hned a nabíjí se zbytek okna.

Od **6:15** se prodává tam, kde je cena aspoň X: první souvislý úsek takových čtvrthodin do 9:00.
Prodává se jen na měřené síti, protože dodávka z druhé přípojky k elektroměru nedojde. Prodej provádí
modul _Prodej do sítě_ (musí být zapnutý), takže běží stejně jako večerní prodej: nejdřív panely,
zastavení na rezervě, zobrazení v kartě Prodej a započtení do statistik. Prodává se do **40 %**.

> **POKUD** je den špatný (předpověď na 8:00–16:00 je 3,0 nebo horší), nebo předpověď chybí
> **PROVEĎ:** ráno neprodávej vůbec.

Baterii se vyplatí vybít jen tehdy, když ji ještě týž den nabije slunce. Ve špatný den bylo noční
nabití koupené na pokrytí domu a jeho prodej by se odpoledne jen draze dokupoval ze sítě. Chybějící
předpověď se bere jako špatný den, protože nic neslibuje, že se baterie znovu nabije. V _Poslední
aktivitě_ je napsané, který z obou důvodů prodej zastavil, a _Plánované akce_ ukážou ráno jako
_Ráno se neprodává_, jakmile to předpověď ví. Kdo chce prodávat i ve špatný den, nastaví
`sell.on-bad-day: true`; prodá se pak jen do `sell.bad-weather-reserve` (**80 %**).

Náhled nočního rozhodnutí je v _Plánovaných akcích_ na stránce Historie, jakmile jsou známé
předpověď a ceny. Každé rozhodnutí i s důvodem je v _Poslední aktivitě_.

---

## Vyrovnání baterie — `automation.balance`

Lithiová baterie srovnává články jen tehdy, když stojí plná. Baterie, která se každý den cykluje a
nahoře skoro nepobude, se pomalu rozbalancuje. Tlačítko **Vyrovnat baterii** v kartě _Ovládání_
na stránce _Ovládání_ ji nabije na **100 %** a pak ji tam **hodinu** (`hold`) drží bez přerušení.

> **POKUD** je baterie na 100 %
> **PROVEĎ:** drž ji tam 60 minut v kuse, pak vrať střídač do jeho režimu.
>
> **POKUD** baterie během držení spadne pod 100 %
> **PROVEĎ:** hodina začne znovu, až bude zase plná.
>
> **POKUD** baterie není plná do 6 hodin (`timeout`)
> **PROVEĎ:** vzdej to a vrať střídač do jeho režimu.

Nabíjí se dálkovým řízením výkonem `power` (**3 000 W**): co dají panely, zbytek se bere ze sítě.
Baterie, které je přikázáno nabíjet, se domem nevybíjí, a tím zůstane nahoře. Odesílají se dvě
relace — jedna dost dlouhá na nabití, druhá na držení s koncem pět minut po něm —, takže se střídač
vrátí sám, i kdyby aplikace spadla. Prodej má přednost: během prodeje se vyrovnání nespustí, a když
prodej začne, vyrovnání ustoupí. Když relaci ukončí jiný modul (noční nabíjení při plné baterii),
pošle se po 3 minutách (`resend-after`) znovu. _Ukončit dálkové řízení_ vyrovnání zruší.

Vpravo od tlačítka je krátce napsané, jestli se ještě nabíjí, nebo už drží, a kolik zbývá. Pás režimu a
graf cen ho kreslí jako nabíjení ze sítě a každý krok je v _Poslední aktivitě_. Samo se nespouští.

---

## Rychlé akce z nástěnky

Ruční zásahy pro to, o čem automatika vědět nemůže. Nic se neplánuje ani nepamatuje:

- **Nabíjení ze sítě** — karta vedle _Prodeje do sítě_. Střídač se sám vrátí do svého režimu, i kdyby
  tahle aplikace spadla. Když nějaké nabíjení běží (odsud, noční nabíjení, vyrovnání baterie), karta
  nad poli napíše kdo ho spustil, jakým výkonem a do kdy.
- **Ovládání** — karta pod nimi se třemi částmi. **Režim střídače** (_Vlastní spotřeba_, _Priorita
  dodávky_, _Záloha_) je trvalý: přežije restart a modul jej může při dalším běhu opět změnit.
  Aktuální režim je na tlačítkách zvýrazněný a změna si zapíše i režim, ze kterého se přepínalo.
  **Vyrovnání baterie** (viz výše). A nakonec **Dálkové řízení** s tlačítkem _Ukončit dálkové
  řízení_, které ukončí jakoukoli běžící relaci — prodej, nabíjení i vyrovnání. Je aktivní jen tehdy,
  když nějaká relace opravdu běží, a řádek nad ním napíše, proč je případně vypnuté.

Dálkové řízení vyžaduje připojení k SolaX Cloud. Prodej se plánuje z karty _Prodej do sítě_.

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

Stav GPIO vstupu (BCM 17) je vidět v dlaždicích na stránce _Ovládání_: _Měřená síť_ při HIGH, _Druhá přípojka_
při LOW. Mimo Raspberry Pi, nebo s `raspberry.enabled: false`, se čte záskok hlásící trvale HIGH —
dlaždice to napíše, aby se náhražka nepletla s měřením.

Každé přehození přepínače se navíc zapisuje do historie (stránka _Historie_), takže je dohledatelné,
kdy se přípojka měnila. Na stránce _Ovládání_ je z těchto záznamů sestavená karta **Režim střídače
dnes**: široký pruh je pracovní režim střídače od půlnoci, tenký pruh pod ním přípojka (šedá měřená
síť, růžová druhá přípojka), oba na jedné časové ose, takže je vidět, do které přípojky se v kterou
hodinu dodávalo. Mimo Pi se pruh přípojky nekreslí — celý den „měřené sítě“, který nikdo neměřil, by
byl výmysl.

Podél spodního okraje pásu režimu běží stužka relací dálkového řízení: **prodej do sítě** fialově,
**nabíjení ze sítě** tyrkysově, dokud je jen naplánované, je dutá. V grafu spotové ceny jsou
intervaly, ve kterých se nabíjelo, označené šrafovaným pruhem pod sloupci — sloupce si nechají barvu
podle ceny, takže je vidět, za kolik se nakupovalo.
Nabíjení se kreslí od začátku do nejbližšího známého konce: do konce, se kterým bylo odeslané
(časované nabíjení z nástěnky, noční nabíjení), nebo do prvního ukončení, prodeje či dalšího
nabíjení po něm. Nabíjení do cílového SOC konec předem nemá — střídač ho ukončí sám, až baterie
cíle dosáhne — proto hlídač střídače zapíše chvíli, kdy relace dálkového řízení skončila, jako
_Nabíjení ze sítě skončilo_.

---

## Změny režimu mimo aplikaci — `solax.control.work-mode-watch`

Střídač nepřepíná jen tato aplikace: režim jde změnit z aplikace SolaX, přímo na displeji střídače
i plánem uloženým v něm — a nic z toho se sem neohlásí. Pracovní režim se proto **jednou za minutu**
(`interval`) čte zpátky a změna, kterou aplikace neudělala, se zapíše do historie jako každá jiná.

> **POKUD** střídač hlásí jiný režim, než jaký měl při minulém čtení,
> **a** není to režim, který aplikace sama krátce předtím zapsala
> **PROVEĎ:** zapiš změnu do historie pod zdroj _Střídač_ — objeví se v _Poslední aktivitě_.

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

Seznam je na vlastní stránce **Historie**, aby stránka _Ovládání_ nekončila seznamem, který roste celý den.
Každý řádek má nadpis a vysvětlení: co modul rozhodl a proč. Kolik řádků je vidět, se přepíná
přímo v hlavičce karty _Poslední aktivita_ (5 až 100) a volba se pamatuje v prohlížeči.

V hlavičce jsou i dva filtry, obojí zapamatované v prohlížeči: **Dnes / Včera / Vše** vybírá den
(historie sahá dva dny zpět) a **Jen změny** schová kontroly, které nic neudělaly — limit dodávky se
přepočítává každou čtvrthodinu a skoro vždy vyjde „není co dělat“, což je 68 řádků denně, pod kterými
se ztratí těch pár, které něco říkají. **Neúspěšná** kontrola zůstane vidět vždycky.

Na stejné stránce _Historie_ jsou i **Plánované akce**. Tytéž záznamy kreslí jejich graf, když se
přepne na _Celý den_: hodiny před
aktuálním časem se doplní tím, co doopravdy proběhlo, potlačeně, aby plán zůstal to první, co je
vidět. Neúspěšný běh je červený. Řádky grafu jsou druhy akcí, ne moduly — _Prodej a nabíjení_,
_Režim střídače_, _Limit dodávky_, _Kontroly_ —, takže i rušný den má čtyři řádky. Který modul akci
udělal, říká tooltip. Když by se dvě akce v řádku překryly, řádek se rozdělí na podřádky a vidět
jsou všechny. Graf **Kvality počasí** se přepíná stejně;
hodiny, které už proběhly, se ukládají průběžně do `data/weather-history.json`, protože předpověď
sama dozadu nevidí. Restart je tedy nesmaže, ale co aplikace nikdy neviděla (třeba ráno před prvním
spuštěním), v grafu chybí — osa i tak zůstane celý den a pod grafem se napíše, odkdy jsou hodiny
zaznamenané.

**Ceny i počasí jdou listovat po dnech.** V hlavičce obou karet na stránce _Ovládání_ je ‹ den zpět,
› den dopředu a mezi nimi jméno dne (_Dnes_, _Včera_, datum), které je samo tlačítkem zpět na dnešek.
Počasí si drží `meteosource.history.retention` (**7 dní**) proběhlých hodin, takže se dá křivka
kteréhokoli dne z minulého týdne postavit vedle dnešní; jiný den než dnešek se kreslí vždy celý a
jako už proběhlý, přepínač _Od teď / Celý den_ zatím odpočívá. Dopředu karta sahá tak daleko do
zítřka, jak daleko sahá předpověď.

Ceny mají stejné omezení u zdroje: cenová služba vrací jen dnešek a zítřek, takže se 96 intervalů
každého dne ukládá, jakmile jsou poprvé zveřejněné — do `ote.history.file`
(`data/price-history.json`), na `ote.history.retention` (**7 dní**), vypíná se to
`ote.history.persist: false`. Den zpět ukazuje ceny tak, jak byly, se stejným šrafováním a podbarvením
toho, co by z nich limit dodávky a prodej udělaly, a řádek nad grafem to říká. _Zítra_ je vždycky
jedno stisknutí daleko a dokud ceny nevyjdou, napíše, kdy vyjdou.

---

## Vyúčtování — SolaxStatistics (`solax-statistics`)

Stránka **Vyúčtování** čte souhrny, které píše samostatná aplikace SolaxStatistics do své složky
`data/summary` (`summary_RRRR-MM.xlsx` a `.json`). Cestu nastavte v `solax-statistics.data-dir` na
složku `data` aplikace SolaxStatistics (výchozí `../SolaxStatistics/data`). Nic se nepřepočítává —
čísla jsou přesně ta, která spočítala SolaxStatistics.

- **Měsíc** — po dnech: výroba, spotřeba, odběr a dodávka rozdělené na měřenou síť (elektroměr ČEZ)
  a druhou přípojku, náklady, výnosy, úspora a bilance (úspora + výnosy − náklady), tři grafy a rozpis.
  V rozpisu přepínač **Barvy podle hodnoty** podbarví každou buňku podle toho, jak vysoká je v rámci
  sloupce (barvou veličiny z grafů); bilance je zelená nad nulou a červená pod ní.
- **Rok** — totéž po měsících; nejnovější sešit nese všechny měsíce zpětně, takže i starší roky mají
  data.

Chybějící složka nebo poškozený soubor se na stránce napíše, aplikaci neshodí. Surová CSV ve
`solax/`, `ote/` a `cez/` se zatím nečtou.

V grafech statistik i vyúčtování jde **kliknutím na položku legendy** řadu skrýt a znovu zobrazit.

---

## Statistiky — `statistics`

Žádná historie, kterou by šlo stáhnout, neexistuje — střídač i cloud hlásí jen *dnešní* čítače a
cenová služba jen *dnešní* ceny. Aplikace proto každých **5 minut** zaznamená denní čítače (výroba,
dodávka, odběr, nabito, vybito), stav baterie, výkon, režim a cenu a drží jeden záznam na den v
`data/statistics.json`. **Statistiky tedy začínají dnem prvního spuštění** a postupně se doplní.
Dny, kdy aplikace neběžela, zůstanou v grafu jako mezera.

Peníze se počítají průběžně: každý přírůstek dodávky a odběru se ocení spotovou cenou čtvrthodiny, ve
které ho střídač nahlásil, a co odešlo během prodeje, se počítá zvlášť jako výnos prodeje. Jde jen o
spotovou cenu bez distribučních poplatků — odhad tržní hodnoty, ne vyúčtování. Den, jehož první
měření přišlo dlouho po půlnoci (první den, restart), má peníze označené jako částečné.

Statistiky mají vlastní stránku **Statistika**. Volí se na ní posledních 7, 30 nebo 90 dní, nebo **kalendářní měsíc**: šipka ‹ vrátí o měsíc
zpět (z klouzavého pohledu rovnou na minulý měsíc), › zase dopředu a ze seznamu jde vybrat kterýkoli
měsíc, za který něco zaznamenáno je. Aktuální měsíc běží od 1. do dneška. Zobrazuje se: dlaždice se součty, energie po dnech, peníze po dnech
(příjem nad osou, náklady pod ní, čárka = čistý výsledek), rozsah nabití baterie po dnech, čas v
jednotlivých režimech a rozbalovací tabulka všech dní. Energie a peníze potřebují připojení k SolaX
Cloudu, samotný Modbus je nehlásí.
