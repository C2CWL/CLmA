package com.clma.llamacpp

/** Unloaded is published only after native cleanup completes, never on a failed unload. */
enum class LocalVisionStatus { Unloaded, Loading, Recognizing, Releasing, ReleaseFailed }
