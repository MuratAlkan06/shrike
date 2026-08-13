# The broker, built in one stage and run in another. What ships is a Temurin 21 JRE and one jar:
# no Maven, no compiler, no source tree, and no build cache.

FROM maven:3.9-eclipse-temurin-21 AS build

# The reactor's toolchains plugin selects a real JDK 21 for javac and for the test fork. This image
# ships one and declares no toolchain for it, so the build would stop before it compiled a line. CI
# gets the same file written for it by its setup-java step; here it is written by hand, naming the
# JAVA_HOME the base image already set.
RUN mkdir -p /root/.m2 && printf '%s\n' \
      '<?xml version="1.0" encoding="UTF-8"?>' \
      '<toolchains>' \
      '  <toolchain>' \
      '    <type>jdk</type>' \
      '    <provides><version>21</version><vendor>temurin</vendor></provides>' \
      "    <configuration><jdkHome>${JAVA_HOME}</jdkHome></configuration>" \
      '  </toolchain>' \
      '</toolchains>' > /root/.m2/toolchains.xml

WORKDIR /workspace
COPY . .

# Tests are skipped here on purpose. CI runs `mvn -B verify` on every push and on every pull
# request, and that is the one place a failing test is meant to stop something. Running the suite
# again during an image build would run process tests inside a container nobody is watching, and
# would fail an image build for a reason CI has already reported — while a green image build would
# still prove nothing that CI had not proved first. Test sources are compiled all the same, because
# `-DskipTests` skips running them rather than building them.
#
# The jar is copied to a fixed name so that the entry point below does not carry a version.
RUN mvn -B -DskipTests package && cp shrike-core/target/shrike-core-*.jar /shrike-core.jar

FROM eclipse-temurin:21-jre

# Nothing in this image runs as root. The id is fixed rather than allocated, so the files in a
# volume belong to the same user across a rebuild. The data directory is created and handed over
# before it becomes a volume below, which is what makes a fresh volume writable by that user: docker
# seeds a new named volume from the image's own directory, ownership included.
RUN groupadd --system --gid 10001 shrike \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin shrike \
    && mkdir -p /var/lib/shrike \
    && chown shrike:shrike /var/lib/shrike

COPY --from=build /shrike-core.jar /opt/shrike/shrike-core.jar

# These are the image's defaults, and two of them are not the broker's own.
#
# SHRIKE_BIND_ADDRESS is 0.0.0.0 here. Publishing a port maps a host port onto a container's own
# interface and never onto its loopback, so a broker bound to loopback inside a container is a
# broker nothing outside it can reach. The java default is the loopback address and stays that way:
# this file is the opt-in, and what it opts into is one network namespace with one port, published
# by whoever runs the image and by nobody else. There is no authentication and no transport
# security in this build, so publishing that port past a trusted network is the operator's decision.
#
# SHRIKE_DATA_DIRECTORY names the volume declared below. SHRIKE_READY_FILE is left unset, so the
# ready file is written to /var/lib/shrike/shrike.ready — inside the volume, like every other path
# the broker derives from its data directory.
ENV SHRIKE_DATA_DIRECTORY=/var/lib/shrike \
    SHRIKE_PORT=9750 \
    SHRIKE_BIND_ADDRESS=0.0.0.0

VOLUME /var/lib/shrike
EXPOSE 9750

USER shrike
WORKDIR /var/lib/shrike

# Healthy means something is listening, and that is all this checks: it opens a TCP connection to the
# port and closes it, sending no request, naming no api key, and appending nothing. A probe that
# spoke the protocol would be a client this image had to carry and keep in step with the broker; a
# probe that read the ready file would say what was true when the broker started rather than what is
# true now. The JRE image ships bash, and bash opens a socket by redirecting onto /dev/tcp, so the
# check costs one shell every interval instead of the JVM a java-based probe would have to start.
#
# The port comes from the environment, and the fallback is the broker's own default rather than the
# ENV above, because those two part company in exactly one case: `docker run -e SHRIKE_PORT=` sets
# the variable to nothing, which the broker reads as unset and answers by listening on 9750. Without
# the fallback the check's target expands to /dev/tcp/127.0.0.1/ and every probe fails, so a broker
# that is serving normally is reported unhealthy for as long as it runs. The two numbers have to be
# kept in step by hand: this one is the broker's default, and it moves when that does.
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SHRIKE_PORT:-9750}"]

# shrike-core depends on the JDK alone, so one jar is the whole classpath. The exec form is what
# makes java pid 1, so `docker stop` reaches it: the JVM answers the SIGTERM by running the
# shrike-broker-stop hook, which closes the broker and forces the segment every partition was
# still writing.
ENTRYPOINT ["java", "-cp", "/opt/shrike/shrike-core.jar", "io.shrike.core.net.BrokerMain"]
