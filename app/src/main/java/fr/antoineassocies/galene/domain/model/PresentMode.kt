package fr.antoineassocies.galene.domain.model

/**
 * Ce que l'utilisateur publie en entrant dans le groupe — le choix
 * « Rien / Micro / Caméra et micro » du formulaire de connexion de Galène.
 */
enum class PresentMode {
    /** Écoute seule : aucune permission demandée, aucun flux publié. */
    NONE,
    MIC,
    MIC_AND_CAMERA;

    val usesMic: Boolean get() = this != NONE
    val usesCamera: Boolean get() = this == MIC_AND_CAMERA
}
