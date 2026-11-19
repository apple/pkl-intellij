package org.pkl.messaging.externalreader;

import java.util.*;

import org.jspecify.annotations.Nullable;

public record ExternalReader(
    String executable, @Nullable List<String> arguments, @Nullable String workingDir) {}
