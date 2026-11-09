"""Build species_catalog_v2.json (BioDex catalogue v2) from v1 plus WoRMS / GBIF.

* keeps the species of the v2 pack index (speciesKey of the SPEX index, or species_key in reference_provenance.jsonl) and their AphiaID;
* checks AphiaID / accepted name against WoRMS (reports; never changes the key; if the v1 AphiaID belongs to another
  taxon it is replaced by the one WoRMS gives for the exact name, and listed under name_mismatch);
* common names in en/es/fr/de/it/pt only from WoRMS vernaculars (CC BY 4.0) or GBIF vernacular names, otherwise the
  scientific name; a v1 name is kept only if it is verified, or if it is not identical to the English one (then flagged
  "v1_unverified" in names_provenance.jsonl);
* descriptions from per-language templates filled with WoRMS classification / habitat flags, IUCN code and MEOW
  ecoregions (no free text);
* optional field `group` (stable English key) derived from the WoRMS classification.

Usage:
  python -I build_catalog_v2.py --catalog species_catalog_v1.json --index species_index_v3.bin \
      --hierarchy ecoregion_hierarchy_v1.json --out C:/rerun_v9/runs/_species_catalog_v2
Network calls are cached in <out>/cache (re-runs do not hit the APIs again).
"""
import argparse
import collections
import hashlib
import json
import re
import sys
import time
from pathlib import Path

import requests

LANGS = ["en", "es", "fr", "de", "it", "pt"]
ISO3 = {"eng": "en", "spa": "es", "fra": "fr", "deu": "de", "ita": "it", "por": "pt"}
WORMS = "https://www.marinespecies.org/rest"
GBIF = "https://api.gbif.org/v1"
UA = {"User-Agent": "oceanguard-catalog/1.0"}
PAUSE = 0.25  # <= 4 requests/s

SOURCES = ["OBIS", "GBIF", "AquaMaps", "WoRMS", "FAO ASFIS"]
LICENSE_NOTE = (
    "MEOW: TNC libre uso con atribución (Spalding et al. 2007). OBIS/GBIF: CC BY 4.0. "
    "AquaMaps: CC BY-NC (verificar antes de uso comercial). "
    "WoRMS (clasificación y nombres comunes): CC BY 4.0. FAO ASFIS (nombres oficiales en inglés, francés y español): CC BY 4.0, atribución a FAO. "
    "Nombres comunes complementarios: GBIF vernacular names (cada conjunto con su licencia, ver names_provenance.jsonl)."
)

# ---------------------------------------------------------------- templates
GROUP_LABEL = {
    "bony_fish": dict(en="bony fish", es="pez óseo", fr="poisson osseux", de="Knochenfisch", it="pesce osseo", pt="peixe ósseo"),
    "sharks_rays": dict(en="shark or ray", es="tiburón o raya", fr="requin ou raie", de="Hai oder Rochen", it="squalo o razza", pt="tubarão ou raia"),
    "turtles": dict(en="sea turtle", es="tortuga marina", fr="tortue marine", de="Meeresschildkröte", it="tartaruga marina", pt="tartaruga marinha"),
    "mammals": dict(en="marine mammal", es="mamífero marino", fr="mammifère marin", de="Meeressäuger", it="mammifero marino", pt="mamífero marinho"),
    "cephalopods": dict(en="cephalopod", es="cefalópodo", fr="céphalopode", de="Kopffüßer", it="cefalopode", pt="cefalópode"),
    "other_molluscs": dict(en="mollusc", es="molusco", fr="mollusque", de="Weichtier", it="mollusco", pt="molusco"),
    "crustaceans": dict(en="crustacean", es="crustáceo", fr="crustacé", de="Krebstier", it="crostaceo", pt="crustáceo"),
    "echinoderms": dict(en="echinoderm", es="equinodermo", fr="échinoderme", de="Stachelhäuter", it="echinoderma", pt="equinoderme"),
    "corals_anemones": dict(en="coral or anemone", es="coral o anémona", fr="corail ou anémone", de="Koralle oder Anemone", it="corallo o anemone", pt="coral ou anémona"),
    "jellyfish_hydrozoans": dict(en="jellyfish or hydrozoan", es="medusa o hidrozoo", fr="méduse ou hydrozoaire", de="Qualle oder Hydrozoon", it="medusa o idrozoo", pt="medusa ou hidrozoário"),
    "sponges": dict(en="sponge", es="esponja", fr="éponge", de="Schwamm", it="spugna", pt="esponja"),
    "polychaetes": dict(en="polychaete worm", es="poliqueto", fr="polychète", de="Vielborster", it="polichete", pt="poliqueta"),
    "ascidians": dict(en="ascidian", es="ascidia", fr="ascidie", de="Seescheide", it="ascidia", pt="ascídia"),
    "seagrasses": dict(en="seagrass", es="fanerógama marina", fr="phanérogame marine", de="Seegras", it="fanerogama marina", pt="fanerogâmica marinha"),
    "algae": dict(en="alga", es="alga", fr="algue", de="Alge", it="alga", pt="alga"),
}
RANK_WORD = {
    "class": dict(en="class", es="clase", fr="classe", de="Klasse", it="classe", pt="classe"),
    "order": dict(en="order", es="orden", fr="ordre", de="Ordnung", it="ordine", pt="ordem"),
    "family": dict(en="family", es="familia", fr="famille", de="Familie", it="famiglia", pt="família"),
}
HABITAT = {
    "isMarine": dict(en="marine", es="marino", fr="marin", de="marin", it="marino", pt="marinho"),
    "isBrackish": dict(en="brackish", es="salobre", fr="saumâtre", de="brackig", it="salmastro", pt="salobro"),
    "isFreshwater": dict(en="freshwater", es="de agua dulce", fr="d'eau douce", de="Süßwasser", it="d'acqua dolce", pt="de água doce"),
    "isTerrestrial": dict(en="terrestrial", es="terrestre", fr="terrestre", de="terrestrisch", it="terrestre", pt="terrestre"),
}
HABITAT_PREFIX = dict(en="Environment (WoRMS): ", es="Entorno (WoRMS): ", fr="Milieu (WoRMS) : ",
                      de="Lebensraum (WoRMS): ", it="Ambiente (WoRMS): ", pt="Ambiente (WoRMS): ")
IUCN = {
    "LC": dict(en="Least Concern", es="Preocupación menor", fr="Préoccupation mineure", de="Nicht gefährdet", it="Minima preoccupazione", pt="Pouco preocupante"),
    "NT": dict(en="Near Threatened", es="Casi amenazada", fr="Quasi menacée", de="Potenziell gefährdet", it="Quasi minacciata", pt="Quase ameaçada"),
    "VU": dict(en="Vulnerable", es="Vulnerable", fr="Vulnérable", de="Gefährdet", it="Vulnerabile", pt="Vulnerável"),
    "EN": dict(en="Endangered", es="En peligro", fr="En danger", de="Stark gefährdet", it="In pericolo", pt="Em perigo"),
    "CR": dict(en="Critically Endangered", es="En peligro crítico", fr="En danger critique", de="Vom Aussterben bedroht", it="In pericolo critico", pt="Criticamente em perigo"),
    "EW": dict(en="Extinct in the Wild", es="Extinta en estado silvestre", fr="Éteinte à l'état sauvage", de="In der Wildnis ausgestorben", it="Estinta in natura", pt="Extinta na natureza"),
    "EX": dict(en="Extinct", es="Extinta", fr="Éteinte", de="Ausgestorben", it="Estinta", pt="Extinta"),
    "DD": dict(en="Data Deficient", es="Datos insuficientes", fr="Données insuffisantes", de="Unzureichende Datengrundlage", it="Carente di dati", pt="Dados insuficientes"),
}
IUCN_T = dict(en="IUCN status: {s} ({c}).", es="Estado UICN: {s} ({c}).", fr="Statut UICN : {s} ({c}).",
              de="IUCN-Status: {s} ({c}).", it="Stato IUCN: {s} ({c}).", pt="Estado UICN: {s} ({c}).")
DIST_COSMO = dict(
    en="Wide-ranging species, not tied to one region in the catalogue.",
    es="Especie de distribución amplia, no ligada a una región en el catálogo.",
    fr="Espèce à large distribution, non liée à une région dans le catalogue.",
    de="Weit verbreitete Art, im Katalog nicht an eine Region gebunden.",
    it="Specie ad ampia distribuzione, non legata a una regione nel catalogo.",
    pt="Espécie de ampla distribuição, não ligada a uma região no catálogo.")
MED = dict(en=", including the Mediterranean", es=", incluido el Mediterráneo", fr=", dont la Méditerranée",
           de=", darunter das Mittelmeer", it=", incluso il Mediterraneo", pt=", incluindo o Mediterrâneo")
DIST_NONE = dict(en="No distribution data in the catalogue.", es="Sin datos de distribución en el catálogo.",
                 fr="Aucune donnée de distribution dans le catalogue.", de="Keine Verbreitungsdaten im Katalog.",
                 it="Nessun dato di distribuzione nel catalogo.", pt="Sem dados de distribuição no catálogo.")


def dist_sentence(lang, n, med):
    m = MED[lang] if med else ""
    one = n == 1
    if lang == "en":
        return f"Recorded in {n} marine ecoregion{'' if one else 's'}{m}."
    if lang == "es":
        return f"Presente en {n} {'ecorregión' if one else 'ecorregiones'} marina{'' if one else 's'}{m}."
    if lang == "fr":
        return f"Présente dans {n} écorégion{'' if one else 's'} marine{'' if one else 's'}{m}."
    if lang == "de":
        return f"In {n} marinen Ökoregion{'' if one else 'en'} nachgewiesen{m}."
    if lang == "it":
        return f"Presente in {n} ecoregion{'e' if one else 'i'} marin{'a' if one else 'e'}{m}."
    return f"Presente em {n} ecorregi{'ão' if one else 'ões'} marinha{'' if one else 's'}{m}."


# ---------------------------------------------------------------- http
class Fetcher:
    def __init__(self, cache: Path):
        self.cache = cache
        self.cache.mkdir(parents=True, exist_ok=True)
        self.s = requests.Session()
        self.s.headers.update(UA)
        self.calls = 0

    def get(self, url, params=None):
        key = hashlib.sha1((url + json.dumps(params or {}, sort_keys=True)).encode()).hexdigest()
        f = self.cache / f"{key}.json"
        if f.exists():
            return json.loads(f.read_text(encoding="utf-8"))
        last = None
        for _ in range(2):
            time.sleep(PAUSE)
            self.calls += 1
            try:
                r = self.s.get(url, params=params, timeout=60)
                if r.status_code in (204, 404):
                    data = None
                else:
                    r.raise_for_status()
                    data = r.json()
                f.write_text(json.dumps({"url": url, "data": data}, ensure_ascii=False), encoding="utf-8")
                return json.loads(f.read_text(encoding="utf-8"))
            except Exception as e:  # noqa: BLE001
                last = e
                time.sleep(2)
        sys.exit(f"FALLO dos veces en {url}: {last}")


def val(c):
    return None if c is None else c["data"]


# ---------------------------------------------------------------- logic
def chain(node):
    out = []
    while node:
        out.append((node["rank"], node["scientificname"]))
        node = node.get("child")
    return out


def derive_group(ch):
    names = {n for _, n in ch}
    ranks = dict(ch)
    has = lambda *x: any(n in names for n in x)  # noqa: E731
    if "Chordata" in names:
        if has("Elasmobranchii", "Holocephali", "Chondrichthyes"):
            return "sharks_rays"
        if has("Actinopteri", "Actinopterygii", "Teleostei", "Cladistia", "Coelacanthi", "Dipnomorpha"):
            return "bony_fish"
        if "Testudines" in names:
            return "turtles"
        if "Mammalia" in names:
            return "mammals"
        if has("Ascidiacea", "Thaliacea", "Appendicularia", "Tunicata"):
            return "ascidians"
    if "Mollusca" in names:
        return "cephalopods" if "Cephalopoda" in names else "other_molluscs"
    if "Crustacea" in names or ranks.get("Class") in ("Malacostraca", "Thecostraca", "Hexanauplia", "Ostracoda"):
        return "crustaceans"
    if "Echinodermata" in names:
        return "echinoderms"
    if "Cnidaria" in names:
        return "corals_anemones" if "Anthozoa" in names else "jellyfish_hydrozoans"
    if "Porifera" in names:
        return "sponges"
    if "Annelida" in names:
        return "polychaetes"
    if has("Tracheophyta"):
        return "seagrasses"
    if has("Rhodophyta", "Chlorophyta", "Ochrophyta", "Phaeophyceae", "Cyanobacteria", "Charophyta"):
        return "algae"
    return None


def resolve_by_name(fetch, name):
    recs = val(fetch.get(f"{WORMS}/AphiaRecordsByName/{name}", {"like": "false", "marine_only": "false"})) or []
    recs = [r for r in recs if r.get("scientificname") == name]
    recs.sort(key=lambda r: r.get("status") != "accepted")
    return recs[0] if recs else None


ASFIS_URL = "https://www.fao.org/fishery/static/ASFIS/ASFIS_sp.zip"
ASFIS_COLS = {"en": "English_name", "fr": "French_name", "es": "Spanish_name"}


def load_asfis(path: Path):
    """FAO ASFIS list (CSV): scientific name -> {en, fr, es}. Downloads the zip if the CSV is not in <out>."""
    if not path.exists():
        import io
        import zipfile
        r = requests.get(ASFIS_URL, headers=UA, timeout=120)
        r.raise_for_status()
        z = zipfile.ZipFile(io.BytesIO(r.content))
        csv_name = next(n for n in z.namelist() if n.lower().endswith(".csv"))
        path.write_bytes(z.read(csv_name))
    import csv
    out = {}
    with path.open(encoding="utf-8-sig", newline="") as f:
        rd = csv.DictReader(f)
        rd.fieldnames = [c.strip() for c in rd.fieldnames]
        for row in rd:
            out[row["Scientific_Name"].strip()] = {lg: (row[c] or "").strip() for lg, c in ASFIS_COLS.items()}
    return out


def worms_names(fetch, aphia):
    """All WoRMS vernaculars of the accepted taxon: ISO-639-3 code -> [names]."""
    res = collections.defaultdict(list)
    for v in val(fetch.get(f"{WORMS}/AphiaVernacularsByAphiaID/{aphia}")) or []:
        res[v.get("language_code")].append(v["vernacular"])
    return res


def gbif_names(fetch, sci):
    """All GBIF vernacular names of the exactly matched species: ISO-639-3 code -> [(name, source)]."""
    m = val(fetch.get(f"{GBIF}/species/match", {"name": sci})) or {}
    if m.get("matchType") != "EXACT" or m.get("rank") != "SPECIES":
        return {}, None
    key = m.get("speciesKey") or m.get("usageKey")
    res = collections.defaultdict(list)
    d = val(fetch.get(f"{GBIF}/species/{key}/vernacularNames", {"limit": 1000})) or {}
    for v in d.get("results", []):
        if v.get("language") and v.get("vernacularName"):
            res[v["language"]].append((v["vernacularName"], v.get("source", "")))
    return res, key


def sentence_case(name, lg):
    """Capitalise only the first letter. Undo Title Case ("Pez León Común" -> "Pez león común"); German keeps its nouns."""
    name = " ".join(name.strip().replace("’", "'").split())
    name = re.sub(r"\s*\(=[^)]*\)", "", name)  # ASFIS notation "Angler(=Monk)" -> "Angler"
    if lg != "de":
        toks = [t for t in name.replace("-", " ").split() if len(t) > 3]
        if len(toks) >= 2 and all(t[:1].isupper() for t in toks):
            name = name[:1] + name[1:].lower()
    return name[:1].upper() + name[1:]


def pick_names(sp, asfis_rec, wn, gn, gkey):
    """Name rule (coordinator, 2026-11-09), same for the six languages:
    1. en/fr/es: the FAO ASFIS name when the species is in ASFIS and has one in that language.
    2. Otherwise keep the v1 name, unless it is empty, identical to the English one (non-English language), a name that
       WoRMS/GBIF list for ANOTHER language (Catalan, Galician, ...) and not for this one, or of another taxon
       (found by the manual review of the final list, see OVERRIDES). Only then: WoRMS/GBIF names with the exact ISO-639-3
       language, the one most repeated across both sources (ties: WoRMS order); if none, the scientific name.
    """
    out, prov = {}, []
    sci = sp["scientificName"]
    old = sp["commonNames"]
    iso = {v: k for k, v in ISO3.items()}
    en_v1 = (old.get("en") or "").strip().lower()
    for lg in LANGS:
        code = iso[lg]
        cands = collections.OrderedDict()  # lowercase -> [display, count, sources]
        for x in wn.get(code, []):
            e = cands.setdefault(sentence_case(x, lg).lower(), [sentence_case(x, lg), 0, set()])
            e[1] += 1; e[2].add("worms")
        for x, src in gn.get(code, []):
            e = cands.setdefault(sentence_case(x, lg).lower(), [sentence_case(x, lg), 0, set()])
            e[1] += 1; e[2].add("gbif")
        other = {sentence_case(x, lg).lower() for c, xs in wn.items() if c != code for x in xs}
        other |= {sentence_case(x, lg).lower() for c, xs in gn.items() if c != code for x, _ in xs}
        v1 = sentence_case(old.get(lg) or "", lg)
        detail = ""
        asf = asfis_rec.get(lg, "") if asfis_rec and lg in ASFIS_COLS else ""
        reason = ""
        if asf:
            name, src, detail = sentence_case(asf, lg), "asfis", "FAO ASFIS (CC BY 4.0, attribution FAO)"
        else:
            bad = None
            if not v1:
                bad = "empty"
            elif "," in v1 or "(" in v1:
                bad = "several_names_in_one"
            elif lg != "en" and v1.lower() == en_v1 and v1.lower() not in cands:
                bad = "identical_to_english"
            elif v1.lower() in other and v1.lower() not in cands:
                bad = "other_language"
            elif (sp["speciesKey"], lg) in OVERRIDES:
                bad = "reviewed_wrong_" + OVERRIDES[(sp["speciesKey"], lg)]
            if bad is None:
                name, src = v1, "v1"
                detail = "verified in " + "+".join(sorted(cands[v1.lower()][2])) if v1.lower() in cands else "not in WoRMS/GBIF"
            else:
                reason = bad
                ranked = sorted((e for k, e in cands.items() if "," not in k and "(" not in k), key=lambda e: -e[1])  # stable: WoRMS order on ties
                if ranked:
                    name = ranked[0][0]
                    src = "worms" if "worms" in ranked[0][2] else "gbif"
                    detail = f"{ranked[0][1]} entries, languages {code}" + (f"; also GBIF {gkey}" if "gbif" in ranked[0][2] else "")
                else:
                    name, src, detail = sci, "scientific", "no verifiable name"
        if (sp["speciesKey"], lg) in CURATED:
            reason = reason or ("curated_over_" + src)
            name, src, detail = CURATED[(sp["speciesKey"], lg)], "curated", "usual name (manual review, 2026-11-09)"
        out[lg] = name
        prov.append({"speciesKey": sp["speciesKey"], "aphiaId": sp["aphiaId"], "lang": lg, "name": name, "source": src,
                     "v1": old.get(lg), "changed": (old.get(lg) or "") != name, "replaced_v1_because": reason, "detail": detail})
    return out, prov


# v1 names found wrong in the manual review of the final lists: (speciesKey, lang) -> reason. Filled after review.
OVERRIDES = {}

# Final names fixed in the manual review (2026-11-09): the usual name in Spain / in English where the ASFIS trade
# name is unusual for divers and beachgoers (e.g. "Caguama", "Tursion", "Alga de vidrieros"), plus accents and
# typos of the ASFIS list. The name that the rule picked stays in names_provenance.jsonl (field "detail" of the rule).
CURATED = {
    ("posidonia_oceanica", "es"): "Posidonia", ("posidonia_oceanica", "en"): "Neptune grass",
    ("caretta_caretta", "es"): "Tortuga boba", ("tursiops_truncatus", "es"): "Delfín mular",
    ("mobula_mobular", "es"): "Manta del Mediterráneo", ("mobula_mobular", "en"): "Giant devil ray",
    ("aurelia_aurita", "es"): "Medusa común", ("trachinus_draco", "es"): "Araña blanca",
    ("coris_julis", "es"): "Doncella", ("stenella_coeruleoalba", "es"): "Delfín listado",
    ("corallium_rubrum", "es"): "Coral rojo", ("corallium_rubrum", "en"): "Red coral",
    ("pinna_nobilis", "es"): "Nacra", ("carcharodon_carcharias", "es"): "Tiburón blanco",
    ("alopias_vulpinus", "es"): "Pez zorro", ("epinephelus_marginatus", "es"): "Mero",
    ("hippocampus_guttulatus", "es"): "Caballito de mar narigudo", ("diodon_hystrix", "es"): "Pez erizo",
    ("sparisoma_cretense", "es"): "Vieja", ("grampus_griseus", "es"): "Delfín de Risso",
    ("ziphius_cavirostris", "es"): "Zifio de Cuvier", ("myliobatis_aquila", "es"): "Águila marina",
    ("sarda_sarda", "es"): "Bonito del Atlántico", ("scomber_scombrus", "es"): "Caballa",
    ("asparagopsis_armata", "es"): "Espárrago marino", ("condylactis_aurantiaca", "es"): "Anémona dorada",
    ("sphaerechinus_granularis", "es"): "Erizo violáceo", ("hexaplex_trunculus", "es"): "Búsano",
    ("dermochelys_coriacea", "es"): "Tortuga laúd", ("spongia_officinalis", "es"): "Esponja de baño",
    ("sargassum_vulgare", "es"): "Sargazo común", ("dasyatis_pastinaca", "es"): "Pastinaca",
    ("patella_ferruginea", "es"): "Lapa ferruginosa", ("patella_ferruginea", "en"): "Ribbed Mediterranean limpet",
    ("patella_ferruginea", "de"): "Fleckige Napfschnecke",
    ("lepidochelys_kempii", "es"): "Tortuga lora", ("thunnus_thynnus", "es"): "Atún rojo",
}


def describe(sp, group, ch, rec, med_ids):
    ranks = dict(ch)
    tax = {"class": ranks.get("Class"), "order": ranks.get("Order"), "family": ranks.get("Family")}
    flags = [f for f in HABITAT if rec.get(f) == 1]
    n = len(sp["ecoregions"])
    med = any(e in med_ids for e in sp["ecoregions"])
    iucn = sp.get("iucnStatus")
    desc = {}
    for lg in LANGS:
        parts = []
        glabel = GROUP_LABEL[group][lg] if group else None
        taxs = ", ".join(f"{RANK_WORD[k][lg]} {v}" for k, v in tax.items() if v)
        if glabel and taxs:
            parts.append(f"{glabel[:1].upper() + glabel[1:]} ({taxs}).")
        elif glabel:
            parts.append(f"{glabel[:1].upper() + glabel[1:]}.")
        elif taxs:
            parts.append(f"{taxs[:1].upper() + taxs[1:]}.")
        if flags:
            parts.append(HABITAT_PREFIX[lg] + ", ".join(HABITAT[f][lg] for f in flags) + ".")
        if iucn in IUCN:
            parts.append(IUCN_T[lg].format(s=IUCN[iucn][lg], c=iucn))
        if sp["cosmopolitan"]:
            parts.append(DIST_COSMO[lg])
        elif n:
            parts.append(dist_sentence(lg, n, med))
        else:
            parts.append(DIST_NONE[lg])
        desc[lg] = " ".join(parts)
    return desc


def index_keys(path: Path):
    """speciesKey set of a SPEX index: 32-byte header (magic, version, n, dim), n*dim float16, then one JSON per row."""
    import struct
    b = path.read_bytes()
    magic, _ver, n, dim = struct.unpack("<4sIII", b[:16])
    if magic != b"XEPS":
        sys.exit(f"{path} is not a SPEX index")
    rows = [json.loads(l) for l in b[32 + n * dim * 2:].decode("utf-8").splitlines() if l.strip()]
    if len(rows) != n:
        sys.exit(f"{path}: {len(rows)} metadata rows for {n} vectors")
    return {r["speciesKey"] for r in rows}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", required=True)
    ap.add_argument("--index", help="species_index_vN.bin (SPEX): species = the speciesKey of its JSON lines (preferred)")
    ap.add_argument("--provenance", help="reference_provenance.jsonl: fallback list of species (only those with photos)")
    ap.add_argument("--hierarchy", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--asfis", help="ASFIS_sp CSV (default: <out>/ASFIS_sp_2026.1.csv, downloaded if missing)")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    fetch = Fetcher(out / "cache")
    asfis = load_asfis(Path(a.asfis) if a.asfis else out / "ASFIS_sp_2026.1.csv")

    v1 = json.loads(Path(a.catalog).read_text(encoding="utf-8"))
    keep = index_keys(Path(a.index)) if a.index else {
        json.loads(l)["species_key"] for l in Path(a.provenance).read_text(encoding="utf-8").splitlines() if l.strip()}
    missing = keep - {s["speciesKey"] for s in v1["species"]}
    if missing:
        sys.exit(f"species of the index absent from the v1 catalogue: {sorted(missing)}")
    hier = json.loads(Path(a.hierarchy).read_text(encoding="utf-8"))
    med_ids = {h["ecoregionId"] for h in hier if h["provinceName"] == "Mediterranean Sea"}
    species = [s for s in v1["species"] if s["speciesKey"] in keep]
    removed = [s["scientificName"] for s in v1["species"] if s["speciesKey"] not in keep]

    new, prov = [], []
    report = {"not_accepted": [], "name_mismatch": [], "no_group": []}
    for i, sp in enumerate(species, 1):
        ap_id = sp["aphiaId"]
        rec = val(fetch.get(f"{WORMS}/AphiaRecordByAphiaID/{ap_id}")) or {}
        if rec.get("scientificname") != sp["scientificName"]:
            # the v1 AphiaID belongs to another taxon: resolve the right one by exact name
            found = resolve_by_name(fetch, sp["scientificName"])
            report["name_mismatch"].append({"key": sp["speciesKey"], "v1_aphiaId": ap_id, "v1_name": sp["scientificName"],
                                            "worms_name_of_v1_id": rec.get("scientificname"),
                                            "resolved_aphiaId": found.get("AphiaID") if found else None})
            if not found:
                sys.exit(f"sin AphiaID para {sp['scientificName']}")
            ap_id = found["AphiaID"]
            rec = found
        if rec.get("status") != "accepted" or rec.get("valid_AphiaID") != ap_id:
            report["not_accepted"].append({"key": sp["speciesKey"], "aphiaId": ap_id, "name": sp["scientificName"],
                                           "status": rec.get("status"), "valid_name": rec.get("valid_name"),
                                           "valid_AphiaID": rec.get("valid_AphiaID")})
        eff = rec.get("valid_AphiaID") or ap_id  # names / classification / habitat come from the accepted taxon
        if eff != ap_id:
            rec = val(fetch.get(f"{WORMS}/AphiaRecordByAphiaID/{eff}")) or rec
        ch = chain(val(fetch.get(f"{WORMS}/AphiaClassificationByAphiaID/{eff}")))
        group = derive_group(ch)
        if group is None:
            report["no_group"].append(sp["speciesKey"])
        wn = worms_names(fetch, eff)
        gn, gkey = gbif_names(fetch, sp["scientificName"])
        asf = asfis.get(sp["scientificName"]) or asfis.get(rec.get("valid_name") or "")
        names, p = pick_names(sp, asf, wn, gn, gkey)
        prov += p
        e = dict(sp)
        e["aphiaId"] = ap_id
        # same field order as v1, new optional field at the end
        e["commonNames"] = names
        e["descriptions"] = describe(sp, group, ch, rec, med_ids)
        e["group"] = group
        new.append(e)
        if i % 25 == 0:
            print(f"{i}/{len(species)} species, {fetch.calls} calls", flush=True)

    cat = {"version": 2, "sources": SOURCES, "license_note": LICENSE_NOTE, "species": new}
    (out / "species_catalog_v2.json").write_text(json.dumps(cat, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (out / "names_provenance.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for p in prov:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")

    summ = {"species": len(new), "removed": removed, "api_calls": fetch.calls,
            "names_by_lang_source": {lg: dict(collections.Counter(p["source"] for p in prov if p["lang"] == lg)) for lg in LANGS},
            "names_changed_by_lang": {lg: sum(1 for p in prov if p["lang"] == lg and p["changed"]) for lg in LANGS},
            "groups": dict(collections.Counter(s["group"] for s in new)), **report}
    (out / "summary.json").write_text(json.dumps(summ, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summ, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
