package com.lanchat.control.device;

import java.util.List;

public record RevocationSnapshotView(
        String organizationId,
        long currentVersion,
        long afterVersion,
        String controlSigningPublicKey,
        String controlKeyFingerprint,
        List<RevocationEntryView> entries
) { }
