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
> **PROVEĎ:** přepni na _FEED_IN_PRIORITY_, aby se přebytek prodal místo aby propadl.

Obě kontroly reagují na skutečně naměřené nabití, ne na předpověď — zachytí i slunečnější
dopoledne, než čekala předpověďová kontrola modulu počasí. Každý kontrolní bod jedná jen ve
"svém" režimu; ostatní režimy (např. _BACKUP_) nechává být.

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
ceny a délku určuje `(min-battery − 40 % rezerva) × 11,6 kWh × 0,92 ÷ 3 950 W`, nejvýše 4 hodiny.
`min-battery` je 50 % — nabití, které prodej stejně vyžaduje; když už je baterie výš, počítá se
to skutečné. Nadhodnotit se nedá, protože stejná hodnota je i práh pro zahájení; podhodnotit ne,
okno by bylo příliš krátké na využití špičky.

Jestli se prodej vůbec vyplatí zahájit, se rozhoduje **znovu při otevření okna**, proti té samé
hodnotě:

> **POKUD** je při startu baterie **< 50 %** (`min-battery`)
> **PROVEĎ:** okno zahoď a napiš do logu proč.

Okno naplánované ručně z nástěnky tuhle hranici ignoruje — je to rozhodnutí člověka —, rezervu
ale nikdy.

Vybíjecí výkon se nekonfiguruje zvlášť — je to `automation.export.power.maximum`, protože prodej
nemůže z instalace odejít rychleji, než kolik smí ven do sítě.

Prodej se provádí **dálkovým řízením**, ne změnou režimu střídače: relace má vlastní dobu trvání a
střídač se po ní sám vrátí do svého režimu — i kdyby tahle aplikace mezitím spadla. Hlídač kontroluje
baterii každé 2 minuty a ukončí relaci dřív, jakmile se dosáhne rezervy 40 %.

Okno lze z nástěnky zrušit (**Zrušit plán**), nahradit jiným (**Přeplánovat**) nebo naplánovat
ručně (**Naplánovat okno**). Ruční okno jde zadat dvěma způsoby: **mezi časy**, nebo **začít hned**
na zvolenou dobu. Varianta „začít hned“ se řídí hodinami aplikace, ne prohlížeče.

---

## Rychlé akce z nástěnky

Vedle karty prodeje sedí **Rychlé akce** — ruční zásahy pro to, o čem automatika vědět nemůže.
Nic se neplánuje ani nepamatuje:

- **Režim střídače** (_Vlastní spotřeba_, _Priorita dodávky_, _Záloha_) — trvalý. Přežije restart
  a modul jej může při dalším běhu opět změnit.
- **Dálkové řízení** (_Nabít ze sítě_, _Prodat do sítě_, _Ukončit dálkové řízení_) — střídač se sám
  vrátí do svého režimu, i kdyby tahle aplikace spadla. Vyžaduje připojení k SolaX Cloud.

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

---

## Historie akcí

Posledních 15 provedených akcí se ukládá do `data/timeline.json`, takže po restartu nástěnka
nezačíná prázdná. Je to jen pohodlí pro zobrazení — trvalým záznamem zůstávají rotující logy
v `logs/`.
