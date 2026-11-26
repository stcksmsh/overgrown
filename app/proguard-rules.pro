# SPDX-License-Identifier: GPL-3.0-only

# Reproducible builds. R8 9.5.29 does not decide deterministically whether the Continuation
# parameter of emitAbort (and of its access$ bridge) can be strengthened to ContinuationImpl:
# the two call each other through emitAbort's own continuation class. Depending on thread timing
# the dex or only the mapping changes, and R8 embeds a hash of the mapping (pg-map-id) in
# classes.dex, while baseline.prof carries the dex checksum, so the APK bytes change.
# Not optimising these two methods removes the race.
-keepclassmembers,allowobfuscation,allowshrinking class kotlinx.coroutines.flow.FlowKt__LimitKt {
    *** *emitAbort*(...);
}
