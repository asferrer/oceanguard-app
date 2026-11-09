package com.oceanguard.ai.inference

/** Localised words of the species and combined reports: table headers, bullets, group names, headings. */
internal object SpeciesReportText {

    val LANGUAGES = listOf("en", "es", "fr", "de", "it", "pt")

    private fun t(en: String, es: String, fr: String, de: String, it: String, pt: String) =
        mapOf("en" to en, "es" to es, "fr" to fr, "de" to de, "it" to it, "pt" to pt)

    private val WORDS: Map<String, Map<String, String>> = mapOf(
        "species" to t("Species", "Especie", "Espèce", "Art", "Specie", "Espécie"),
        "scientific" to t("Scientific name", "Nombre científico", "Nom scientifique", "Wissenschaftlicher Name", "Nome scientifico", "Nome científico"),
        "group" to t("Group", "Grupo", "Groupe", "Gruppe", "Gruppo", "Grupo"),
        "iucn" to t("IUCN", "UICN", "UICN", "IUCN", "IUCN", "UICN"),
        "observations" to t("Observations", "Observaciones", "Observations", "Beobachtungen", "Osservazioni", "Observações"),
        "photos" to t("Photos", "Fotos", "Photos", "Fotos", "Foto", "Fotos"),
        "outOfRange" to t("Out of range", "Fuera de rango", "Hors aire de répartition", "Außerhalb des Verbreitungsgebiets", "Fuori areale", "Fora da área de distribuição"),
        "confidence" to t("Mean confidence (%)", "Confianza media (%)", "Confiance moyenne (%)", "Mittlere Konfidenz (%)", "Confidenza media (%)", "Confiança média (%)"),
        "firstSeen" to t("First seen", "Primera vez", "Première observation", "Erstmals gesehen", "Prima osservazione", "Primeira vez"),
        "lastSeen" to t("Last seen", "Última vez", "Dernière observation", "Zuletzt gesehen", "Ultima osservazione", "Última vez"),
        "date" to t("Date", "Fecha", "Date", "Datum", "Data", "Data"),
        "lat" to t("Latitude", "Latitud", "Latitude", "Breitengrad", "Latitudine", "Latitude"),
        "lon" to t("Longitude", "Longitud", "Longitude", "Längengrad", "Longitudine", "Longitude"),
        "id" to t("Observation", "Observación", "Observation", "Beobachtung", "Osservazione", "Observação"),
        "speciesCount" to t("Species", "Especies", "Espèces", "Arten", "Specie", "Espécies"),
        "debrisItems" to t("Debris objects", "Objetos de residuo", "Objets de déchets", "Abfallobjekte", "Oggetti di rifiuto", "Objetos de resíduo"),
        "health" to t("Health score", "Puntuación de salud", "Score de santé", "Gesundheitswert", "Punteggio di salute", "Pontuação de saúde"),
        "nearby" to t("Species within 1 km (observations)", "Especies a menos de 1 km (observaciones)", "Espèces à moins de 1 km (observations)", "Arten im Umkreis von 1 km (Beobachtungen)", "Specie entro 1 km (osservazioni)", "Espécies a menos de 1 km (observações)"),
        "session" to t("Photo", "Foto", "Photo", "Foto", "Foto", "Foto"),
        "none" to t("none", "ninguna", "aucune", "keine", "nessuna", "nenhuma"),
        "totalObs" to t("Species observations", "Observaciones de especies", "Observations d'espèces", "Artbeobachtungen", "Osservazioni di specie", "Observações de espécies"),
        "totalPhotos" to t("Photos with species", "Fotos con especies", "Photos avec espèces", "Fotos mit Arten", "Foto con specie", "Fotos com espécies"),
        "totalSpecies" to t("Distinct species", "Especies distintas", "Espèces distinctes", "Verschiedene Arten", "Specie distinte", "Espécies distintas"),
        "totalGroups" to t("Taxonomic groups", "Grupos taxonómicos", "Groupes taxonomiques", "Taxonomische Gruppen", "Gruppi tassonomici", "Grupos taxonômicos"),
        "totalThreatened" to t("Threatened species (IUCN VU, EN, CR)", "Especies amenazadas (UICN VU, EN, CR)", "Espèces menacées (UICN VU, EN, CR)", "Gefährdete Arten (IUCN VU, EN, CR)", "Specie minacciate (IUCN VU, EN, CR)", "Espécies ameaçadas (UICN VU, EN, CR)"),
        "totalOut" to t("Species outside their usual range", "Especies fuera de su rango habitual", "Espèces hors de leur aire habituelle", "Arten außerhalb ihres üblichen Verbreitungsgebiets", "Specie fuori dal loro areale abituale", "Espécies fora da área habitual"),
        "totalLocated" to t("Observations with GPS position", "Observaciones con posición GPS", "Observations avec position GPS", "Beobachtungen mit GPS-Position", "Osservazioni con posizione GPS", "Observações com posição GPS"),
        "period" to t("Period", "Periodo", "Période", "Zeitraum", "Periodo", "Período"),
        "debrisTotal" to t("Debris objects", "Objetos de residuo", "Objets de déchets", "Abfallobjekte", "Oggetti di rifiuto", "Objetos de resíduo"),
        "debrisPhotos" to t("Photos analysed for debris", "Fotos analizadas por residuos", "Photos analysées pour les déchets", "Auf Abfall analysierte Fotos", "Foto analizzate per i rifiuti", "Fotos analisadas por resíduos"),
        "unknownGroup" to t("Not classified", "Sin clasificar", "Non classé", "Nicht klassifiziert", "Non classificato", "Não classificado"),
        // Sections.
        "hSpeciesSummary" to t("Summary", "Resumen", "Résumé", "Zusammenfassung", "Sommario", "Resumo"),
        "hSpeciesList" to t("Species Observed", "Especies Observadas", "Espèces Observées", "Beobachtete Arten", "Specie Osservate", "Espécies Observadas"),
        "hGroups" to t("Taxonomic Groups", "Grupos Taxonómicos", "Groupes Taxonomiques", "Taxonomische Gruppen", "Gruppi Tassonomici", "Grupos Taxonômicos"),
        "hThreatened" to t("Conservation Status", "Estado de Conservación", "Statut de Conservation", "Erhaltungszustand", "Stato di Conservazione", "Estado de Conservação"),
        "hOutOfRange" to t("Sightings Outside the Usual Range", "Observaciones Fuera del Rango Habitual", "Observations Hors de l'Aire Habituelle", "Sichtungen außerhalb des üblichen Verbreitungsgebiets", "Osservazioni Fuori dall'Areale Abituale", "Observações Fora da Área Habitual"),
        "hLocations" to t("Where They Were Seen", "Dónde se Observaron", "Où Elles ont Été Vues", "Wo sie gesehen wurden", "Dove Sono State Viste", "Onde Foram Vistas"),
        "hConclusions" to t("Conclusions and Recommendations", "Conclusiones y Recomendaciones", "Conclusions et Recommandations", "Schlussfolgerungen und Empfehlungen", "Conclusioni e Raccomandazioni", "Conclusões e Recomendações"),
        "hCombinedSummary" to t("Summary of the Area", "Resumen del Área", "Résumé de la Zone", "Zusammenfassung des Gebiets", "Sommario dell'Area", "Resumo da Área"),
        "hDebris" to t("Marine Debris", "Residuos Marinos", "Déchets Marins", "Meeresabfall", "Rifiuti Marini", "Resíduos Marinhos"),
        "hBiodiversity" to t("Biodiversity", "Biodiversidad", "Biodiversité", "Biodiversität", "Biodiversità", "Biodiversidade"),
        "hTogether" to t("Debris and Species Together", "Residuos y Especies Juntos", "Déchets et Espèces Ensemble", "Abfall und Arten zusammen", "Rifiuti e Specie Insieme", "Resíduos e Espécies Juntos"),
        "hCombinedConclusions" to t("Conclusions", "Conclusiones", "Conclusions", "Schlussfolgerungen", "Conclusioni", "Conclusões"),
    )

    private val GROUPS: Map<String, Map<String, String>> = mapOf(
        "bony_fish" to t("Bony fish", "Peces óseos", "Poissons osseux", "Knochenfische", "Pesci ossei", "Peixes ósseos"),
        "sharks_rays" to t("Sharks and rays", "Tiburones y rayas", "Requins et raies", "Haie und Rochen", "Squali e razze", "Tubarões e raias"),
        "turtles" to t("Sea turtles", "Tortugas marinas", "Tortues marines", "Meeresschildkröten", "Tartarughe marine", "Tartarugas marinhas"),
        "mammals" to t("Marine mammals", "Mamíferos marinos", "Mammifères marins", "Meeressäuger", "Mammiferi marini", "Mamíferos marinhos"),
        "cephalopods" to t("Cephalopods", "Cefalópodos", "Céphalopodes", "Kopffüßer", "Cefalopodi", "Cefalópodes"),
        "other_molluscs" to t("Other molluscs", "Otros moluscos", "Autres mollusques", "Andere Weichtiere", "Altri molluschi", "Outros moluscos"),
        "crustaceans" to t("Crustaceans", "Crustáceos", "Crustacés", "Krebstiere", "Crostacei", "Crustáceos"),
        "echinoderms" to t("Echinoderms", "Equinodermos", "Échinodermes", "Stachelhäuter", "Echinodermi", "Equinodermos"),
        "corals_anemones" to t("Corals and anemones", "Corales y anémonas", "Coraux et anémones", "Korallen und Anemonen", "Coralli e anemoni", "Corais e anêmonas"),
        "jellyfish_hydrozoans" to t("Jellyfish and hydrozoans", "Medusas e hidrozoos", "Méduses et hydrozoaires", "Quallen und Hydrozoen", "Meduse e idrozoi", "Águas-vivas e hidrozoários"),
        "sponges" to t("Sponges", "Esponjas", "Éponges", "Schwämme", "Spugne", "Esponjas"),
        "polychaetes" to t("Polychaete worms", "Poliquetos", "Polychètes", "Vielborster", "Policheti", "Poliquetas"),
        "ascidians" to t("Ascidians", "Ascidias", "Ascidies", "Seescheiden", "Ascidie", "Ascídias"),
        "seagrasses" to t("Seagrasses", "Plantas marinas", "Herbiers marins", "Seegräser", "Praterie marine", "Ervas marinhas"),
        "algae" to t("Algae", "Algas", "Algues", "Algen", "Alghe", "Algas"),
    )

    fun word(key: String, lang: String): String = (WORDS[key] ?: error("Unknown word $key"))[normalize(lang)]!!

    /** The group name in [lang]; a group the app does not know is shown by its key with spaces. */
    fun groupName(group: String, lang: String): String =
        if (group == SpeciesReportContext.UNKNOWN_GROUP) {
            word("unknownGroup", lang)
        } else {
            GROUPS[group]?.get(normalize(lang)) ?: group.replace('_', ' ')
        }

    fun normalize(lang: String): String = if (lang in LANGUAGES) lang else "en"

    /** Every word of every language, for tests that check each one is translated. */
    internal fun allWords(): Map<String, Map<String, String>> = WORDS + GROUPS
}
