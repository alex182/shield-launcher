# Android TV launcher build environment.
# JDK 17 + Android SDK (API 34 / build-tools 34) + Gradle, no host pollution.
FROM eclipse-temurin:17-jdk

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip git curl android-sdk-platform-tools-common ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# cmdline-tools comes from the build context so we don't re-download 153MB.
COPY context/cmdline-tools.zip /tmp/cmdline-tools.zip
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/cmdline-tools.zip

ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true \
    && sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null \
    && chmod -R a+rwX ${ANDROID_HOME}

# Gradle 8.5 (AGP 8.1.x needs Gradle 8.0+ and JDK 17)
RUN curl -sSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.5-bin.zip \
    && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip \
    && ln -s /opt/gradle-8.5/bin/gradle /usr/local/bin/gradle

# Build as a normal user with the host's UID, so files written into the bind
# mount are owned by the host user rather than root. Without this you cannot
# delete your own build/ directory without sudo.
ARG UID=1000
ARG GID=1000
# The temurin base already ships an "ubuntu" user at 1000:1000, so free the
# UID before claiming it.
RUN set -eux; \
    if getent passwd ${UID} >/dev/null; then \
        userdel -r "$(getent passwd ${UID} | cut -d: -f1)" 2>/dev/null || true; \
    fi; \
    if ! getent group ${GID} >/dev/null; then groupadd -g ${GID} dev; fi; \
    useradd -m -u ${UID} -g ${GID} -s /bin/bash dev; \
    mkdir -p /home/dev/.gradle /home/dev/.android; \
    chown -R ${UID}:${GID} /home/dev

# Stable debug keystore: regenerating it would change the signing identity and
# force an uninstall/reinstall on the device for every rebuild.
USER dev
RUN keytool -genkeypair -v \
      -keystore /home/dev/.android/debug.keystore \
      -storepass android -keypass android -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10950 \
      -dname "CN=Android Debug,O=Android,C=US" > /dev/null 2>&1

ENV HOME=/home/dev \
    GRADLE_USER_HOME=/home/dev/.gradle
WORKDIR /workspaces/shield-launcher
