package com.vboard.core.model

/** The role a model pack plays in the VBoard pipeline. */
enum class ModelKind { STREAMING_ASR, FINAL_ASR, REFINER_LLM }

/**
 * One downloadable file belonging to a [ModelPack].
 *
 * @property relativePath path under the pack's install dir, e.g. "encoder.int8.onnx".
 * @property url download URL.
 * @property sha256 lowercase hex digest; empty string = skip verification (used until final
 *   hashes are pinned).
 * @property sizeBytes expected size of the file in bytes.
 * @property archive true when [url] points at an archive (e.g. tar.bz2); [relativePath] is then
 *   the archive filename and the app layer extracts it after install. The download/install engine
 *   itself is archive-agnostic: it just downloads and verifies the archive file.
 */
data class ModelFileSpec(
    val relativePath: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val archive: Boolean = false,
)

/**
 * @property required true only for packs without which VBoard cannot transcribe speech at all.
 *   This is deliberately the *smallest* set that makes dictation work, not "everything the
 *   pipeline can use": an optional pack is an upgrade the user opts into later, and setup must
 *   never be gated on one. [ModelReadiness] is the single place that answers "installed enough
 *   to dictate?" — do not re-derive it by counting packs.
 */
data class ModelPack(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    val version: Int,
    val files: List<ModelFileSpec>,
    val licenseNote: String,
    val required: Boolean,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /**
     * Peak disk the install needs. An archive is extracted before it is deleted, so it
     * briefly coexists with its contents; 2.5x the compressed size covers a bz2 of
     * already-quantized ONNX weights (which compress poorly) plus the archive itself.
     */
    val installFootprintBytes: Long
        get() = files.sumOf { if (it.archive) it.sizeBytes * 5 / 2 else it.sizeBytes }
}

/**
 * Static catalog of the model packs VBoard knows how to download.
 *
 * Empty sha256 means "skip verification"; hashes get pinned once a release
 * process snapshots the upstream artifacts. Sizes seed the progress UI and the
 * storage pre-check only - the installer asks the server for each file's real
 * length and never treats these numbers as a completion gate.
 */
object ModelCatalog {

    private const val SHERPA_RELEASE_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /**
     * Hugging Face revision the refiner download is pinned to.
     *
     * This MUST become an immutable commit SHA before the refiner is ever marked
     * [ModelPack.required] or shipped with a digest: `main` is a mutable branch ref, so the
     * bytes behind the URL can change under us between releases and nothing would notice.
     * It is still `main` here only because this build environment cannot reach
     * huggingface.co to read the current commit — inventing a 40-hex SHA would 404 every
     * user's download, which is strictly worse than a mutable ref.
     *
     * The release pipeline fills this in together with the file's [ModelFileSpec.sha256].
     * `ModelCatalogTest` fails the build if an unpinned pack is ever marked required, so the
     * gap can never reach a user on the critical path.
     */
    private const val REFINER_REVISION = "main"

    private const val REFINER_BASE =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve"

    val packs: List<ModelPack> = listOf(
        ModelPack(
            id = "zipformer-en-streaming",
            displayName = "Live transcription (English)",
            kind = ModelKind.STREAMING_ASR,
            version = 1,
            files = listOf(
                ModelFileSpec(
                    relativePath = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
                    url = "$SHERPA_RELEASE_BASE/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
                    sha256 = "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9",
                    sizeBytes = 127_887_156L, // measured from the release asset
                    archive = true,
                ),
            ),
            licenseNote = "sherpa-onnx streaming Zipformer 20M, Apache-2.0",
            required = true,
        ),
        ModelPack(
            id = "parakeet-tdt-0.6b-v2",
            displayName = "High-accuracy transcription (English)",
            kind = ModelKind.FINAL_ASR,
            version = 1,
            files = listOf(
                ModelFileSpec(
                    relativePath = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
                    url = "$SHERPA_RELEASE_BASE/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
                    sha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
                    sizeBytes = 482_468_385L, // measured from the release asset
                    archive = true,
                ),
            ),
            licenseNote = "sherpa-onnx NeMo Parakeet TDT 0.6B v2, CC-BY-4.0",
            // Optional accuracy upgrade, not a dependency. Dictation runs on the streaming
            // Zipformer alone (the final pass is a re-scoring step the pipeline already
            // degrades past — PRODUCT_SPEC VB-124's Zipformer-only mode), so requiring this
            // pack turned a 128 MB first run into a 610 MB one with no way past it.
            required = true,
        ),
        ModelPack(
            // Qwen is used as the default refiner because litert-community hosts
            // it ungated (a Gemma .task requires a Hugging Face license
            // acceptance + auth token, which a keyboard can't ask for mid-setup).
            id = "qwen25-05b-refiner",
            displayName = "Smart cleanup (on-device LLM)",
            kind = ModelKind.REFINER_LLM,
            version = 1,
            files = listOf(
                ModelFileSpec(
                    relativePath = "qwen2.5-0.5b-instruct-q8.task",
                    url = "$REFINER_BASE/$REFINER_REVISION/" +
                        "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                    // Intentionally empty: the digest must be filled in from the release
                    // pipeline, which is the only place that can fetch the artifact and hash
                    // it. Empty means "skip verification" (see ModelFileSpec); a fabricated
                    // hash would fail every install instead of none. Pair it with a pinned
                    // REFINER_REVISION in the same change.
                    sha256 = "",
                    sizeBytes = 547_000_000L, // estimate; the installer uses the server's length
                ),
            ),
            licenseNote = "Qwen2.5, Apache-2.0 (LiteRT community build)",
            required = false,
        ),
    )

    fun byId(id: String): ModelPack? = packs.firstOrNull { it.id == id }

    fun byKind(kind: ModelKind): List<ModelPack> = packs.filter { it.kind == kind }

    /** Packs setup genuinely cannot finish without. See [ModelReadiness]. */
    val requiredPacks: List<ModelPack> get() = packs.filter { it.required }

    /** Opt-in upgrades: never gate setup, never appear in the "you need" figure. */
    val optionalPacks: List<ModelPack> get() = packs.filterNot { it.required }
}
