package fr.antoineassocies.galene.domain.model

/**
 * What the user publishes on joining the group — the "Nothing / Microphone /
 * Camera and microphone" choice of Galène's login form.
 */
enum class PresentMode {
    /** Listen only: no permission requested, no stream published. */
    NONE,
    MIC,
    MIC_AND_CAMERA;

    val usesMic: Boolean get() = this != NONE
    val usesCamera: Boolean get() = this == MIC_AND_CAMERA
}
