# Build-only image for the Simple Video Player Android app.
# Compilation happens here; the resulting APK is run/installed on the host.
FROM eclipse-temurin:17-jdk-jammy

ARG USER_ID=1000
ARG GROUP_ID=1000

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV GRADLE_HOME=/opt/gradle
ENV ANDROID_PLATFORM=android-35
ENV ANDROID_BUILD_TOOLS=35.0.0
ENV CMDLINE_TOOLS_VERSION=11076708
ENV GRADLE_VERSION=8.9
ENV PATH=$PATH:$GRADLE_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin

RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip wget ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Android command-line tools + required SDK packages.
RUN mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" && \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools" && \
    mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest" && \
    rm /tmp/cmdline-tools.zip && \
    yes | sdkmanager --licenses >/dev/null && \
    sdkmanager "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}" >/dev/null

# Standalone Gradle (so builds do not depend on the wrapper download at runtime).
RUN wget -q "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    mv "/opt/gradle-${GRADLE_VERSION}" "$GRADLE_HOME" && \
    rm /tmp/gradle.zip

# Make the SDK writable by the build user and create a matching host user.
RUN chmod -R a+rwX "$ANDROID_SDK_ROOT" && \
    (groupadd -g "$GROUP_ID" builder 2>/dev/null || true) && \
    (useradd -m -u "$USER_ID" -g "$GROUP_ID" builder 2>/dev/null || true)

ENV GRADLE_USER_HOME=/home/builder/.gradle
RUN mkdir -p "$GRADLE_USER_HOME" && chown -R "$USER_ID:$GROUP_ID" /home/builder

USER builder
WORKDIR /workspace
