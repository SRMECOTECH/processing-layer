# Docker, told through our own GPS platform

Every concept in this guide comes with the place it shows up in **this repository** and the real numbers from
**this laptop**, as measured on 2026-10-27. Nothing is hypothetical unless a section says so ("could have been
implemented"). Read it top to bottom once. After that, [section 10](#10-cheat-sheet) is the one-page reminder.

---

## 0. The story in one minute

Our platform needs PostgreSQL 14, Kafka 4.3, Redis Stack, two Spring Boot apps on Java 21, a Next.js app on
Node 22, and GraphHopper. Installing all of that by hand on every laptop, on the HP home server and later on a
cloud VM gives you a different setup each time, and that is where "works on my machine" bugs come from.

Docker's answer is: **package each program together with everything it needs (its OS libraries, runtime and
config) into one file-system snapshot called an *image*, and run that image as an isolated process called a
*container*.** The same image runs the same way on your Windows laptop, the Ubuntu HP and a Google or Azure server.

Then **Compose** is the one YAML file that says "these 6 to 8 containers, wired together like this, with these
ports, disks and passwords". `docker compose up -d` starts the whole platform. That's the story. The rest of this
guide covers each piece.

---

## 1. The cast: what each word means, and where it lives in our system

| Word | What it really is | In our system |
|---|---|---|
| **Image** | A read-only, layered snapshot of a file system plus metadata (which command to run, which user, which ports). It's a template, and it never changes once built. | `srmecotech/processing-layer:latest` (628 MB), `postgres:14.22` (628 MB), `redis/redis-stack:7.4.0-v8` (1.42 GB). Run `docker images` to see all 11. |
| **Layer** | Each Dockerfile instruction (`COPY`, `RUN`) adds one layer, a diff on top of the previous one. Layers are shared between images and cached between builds. | Our gps-service image = Ubuntu base + Java 21 JRE (shared with route-service) + one **89.9 MB layer that is just our jar**. Run `docker history srmecotech/processing-layer:latest`. |
| **Container** | A running (or stopped) instance of an image: the image's layers plus one thin writable layer on top, run as a normal Linux process with isolation around it. | `gps-streaming-platform-gps-service-1`. Inside it, **PID 1 is `java`**, the user is `gps` (uid 1001) and the OS is Ubuntu 26.04. A container is a process, not a machine. |
| **Dockerfile** | The recipe that builds an image, step by step. | [`Dockerfile`](../Dockerfile) (gps-service + route-service), [`gps-dashboard-ui/Dockerfile`](../gps-dashboard-ui/Dockerfile), [`infrastructure/routing/graphhopper/Dockerfile`](../infrastructure/routing/graphhopper/Dockerfile). |
| **Registry** | A server that stores images: Docker Hub, Google Artifact Registry, Azure Container Registry, GitHub GHCR. `docker pull` and `docker push` talk to it. | Postgres, Kafka and Redis come from Docker Hub. Our own images carry Docker Hub names (`srmecotech/...`), so `docker compose pull gps-service` can fetch a ready-made one. |
| **Tag** | A movable label on an image (`:14.22`, `:latest`). A **digest** (`@sha256:…`) is the immutable one. | We pin exact versions for third-party images (`postgres:14.22`, `apache/kafka:4.3.1`) and use `:latest` for our own (see the gaps in section 8). |
| **Volume** | Storage that Docker manages **outside** the container's writable layer. It survives the container being deleted and recreated. | `gps-postgres-data` (all trips), `gps-kafka-data`, `gps-redisinsight-data`, `gps-routing-data` (the 1.6 GB India map). |
| **Bind mount** | A host folder mounted into a container. | Used by our scripts only: `install-routing-graph.sh` mounts the tar's folder read-only (`-v "$DIR:/in:ro"`). |
| **Network** | A virtual switch. Containers on the same network reach each other **by name**, through Docker's built-in DNS. | `gps-streaming-platform_default` (bridge). gps-service sits at 172.18.0.3 and connects to `postgres:5432`, `kafka:9092` and `redis:6379`, never to IPs. |
| **Port publishing** | `HOST:CONTAINER`, which opens a port on your machine and forwards it into the container. | `5433:5432` puts Postgres at localhost:5433 on Windows, so it doesn't clash with your Windows Postgres on 5432. Same idea for `6380:6379` and `29092:29092`. |
| **Docker Engine** | The background service (`dockerd`) that builds images and runs containers. Under it sit **containerd** (manages container lifecycles) and **runc** (actually creates the isolated process). | Version 29.8.2. On Windows it runs inside a Linux VM (next row). |
| **Docker CLI** | The `docker` command. It only sends API calls to the engine. | What you type in PowerShell. |
| **Docker Desktop** | The Windows/Mac app: a small Linux VM (on WSL2 for us) + the engine inside it + the GUI + `docker` CLI wiring. | 16 CPUs and 12.4 GB RAM given to the VM; all data in `docker_data.vhdx`, which we compacted today from 36.1 GB to 13.8 GB. |
| **Compose** | A tool (`docker compose`) that reads one YAML file describing many containers, and creates and updates them together. | [`docker-compose.yml`](../docker-compose.yml) with project name `gps-streaming-platform`. |
| **YAML** | Just the file format: indentation-based key/value text. It isn't Docker-specific; Kubernetes, GitHub Actions and Spring's `application.yml` all use it. | `docker-compose.yml`, `infrastructure/home-server/docker-compose.yml`, `infrastructure/routing/graphhopper/config.yml`. |
| **BuildKit** | The modern build engine behind `docker build`. It enables cache mounts, multi-stage builds that skip unused stages, `ADD --checksum` and parallel stages. | `RUN --mount=type=cache,target=/root/.m2` keeps Maven downloads between builds. |

---

## 2. What a container really is (the part that wins debates)

A container is **not a small VM**. It is an ordinary Linux process that the kernel isolates with three features:

1. **Namespaces** control what the process can *see*: its own process list (that's why `java` is PID 1 inside our
   container), its own network interface and IP (172.18.0.3), its own hostname, its own mount table and its own
   users.
2. **cgroups** control what the process can *use*: CPU, memory and I/O limits. Our containers have **no limits**
   (`mem=0` in `docker inspect`), so section 8 lists that as a gap.
3. **Union file system (overlayfs)** stacks the image's read-only layers and puts one writable layer on top. When
   you delete the container, the writable layer goes with it. **That's why data must live in volumes.**

So:

| | Virtual machine | Container |
|---|---|---|
| Kernel | Its own guest kernel | **Shares the host's kernel** |
| Boots in | Tens of seconds | Milliseconds (it's just starting a process) |
| Size | GBs (a full OS) | MBs to hundreds of MBs (only user-space files) |
| Isolation | Strong (hardware-virtualised) | Weaker (shared kernel), hardened with seccomp, AppArmor/SELinux, rootless and user namespaces |
| Ours | The WSL2 VM that Docker Desktop runs | The 6 to 8 platform containers inside that VM |

**Why Windows needs a VM at all:** containers share the host's *Linux* kernel, and Windows doesn't have one. So
Docker Desktop runs one lightweight Linux VM (WSL2), and every container runs inside it. That's why:

- `docker info` says the OS is "Docker Desktop" and the server OS is `linux`.
- All your images and volumes live inside one virtual disk file, `docker_data.vhdx`, which only grows until you
  compact it.
- `localhost:5433` works from Windows only because Docker Desktop forwards ports out of the VM.
- On the HP with Ubuntu there's **no VM**: the containers run directly on its kernel, which is one reason an 8 GB
  machine is enough.

(Windows containers also exist. They share a *Windows* kernel and run Windows-only workloads like .NET Framework or
IIS. We don't use them, and almost the whole industry runs Linux containers.)

**Standards behind it (OCI):** the image format and the runtime are open standards from the Open Container
Initiative. An image built by Docker runs on containerd, CRI-O, Podman, Kubernetes, Google Cloud Run and Azure
Container Apps. Docker is one tool that builds and runs OCI images, and it isn't the only one.

---

## 3. Our platform as Docker sees it

```
 Windows (your laptop)                         WSL2 Linux VM (Docker Desktop)
 ─────────────────────                         ─────────────────────────────────────────────────────────
 browser → localhost:3000 ───────────────────▶ dashboard      (node:22-alpine, our image)
                                                   │  GPS_API_URL=http://gps-service:8080   ← DNS by name
 browser → localhost:8080 ───────────────────▶ gps-service    (Java 21, our image, PID 1 = java)
                                                   ├──▶ postgres:5432   ──▶ volume gps-postgres-data
 psql    → localhost:5433 ───────────────────▶ postgres          
                                                   ├──▶ redis:6379      (no volume on purpose: positions rebuild)
 redis   → localhost:6380 / :8001 ───────────▶ redis-stack     
                                                   ├──▶ kafka:9092      ──▶ volume gps-kafka-data
 tools   → localhost:29092 ──────────────────▶ kafka (EXTERNAL listener)
 browser → localhost:8085 ───────────────────▶ kafka-ui ──▶ kafka:9092
                                                   └──▶ host.docker.internal:3306 ──▶ MySQL smart_truck on Windows

 profile "routing" (off unless asked for):
 browser → localhost:8989 ───────────────────▶ graphhopper   ──▶ volume gps-routing-data (map + graph)
 browser → localhost:8090 ───────────────────▶ route-service ──▶ graphhopper:8989 (or the HP's IP)

 All of the above share one network: gps-streaming-platform_default
```

Three details here are worth knowing well, because they're the questions people ask:

**a) Two ways to reach Kafka.** Kafka tells each client which address to reconnect to (the "advertised listener").
A container must be told `kafka:9092`, while a Windows tool must be told `localhost:29092`. So our Kafka has two
listeners: `INTERNAL://kafka:9092` and `EXTERNAL://localhost:29092`. This is the most common Kafka-in-Docker bug,
and we've solved it in [`docker-compose.yml`](../docker-compose.yml) lines 66–67.

**b) Container → Windows.** `host.docker.internal` is a special name for "the host machine". gps-service uses it to
read the MySQL `smart_truck` database that's installed on Windows. `extra_hosts: host.docker.internal:host-gateway`
makes it work on plain Linux too, which matters for the HP.

**c) Container ports vs host ports.** Inside the network, Postgres is always `postgres:5432`. The `5433` exists
only on the Windows side. Containers never use published ports to talk to each other.

---

## 4. Our Dockerfiles, line by line

### 4.1 [`Dockerfile`](../Dockerfile): gps-service and route-service

```dockerfile
FROM eclipse-temurin:21-jdk AS build          # stage 1 starts from a full JDK (javac, Maven needs it)
WORKDIR /workspace                            # cd, and create the folder if missing
COPY .mvn .mvn                                # copy from the build context (the repo, minus .dockerignore)
COPY mvnw pom.xml ./
COPY gps-configuration gps-configuration      # ... every module's sources
RUN --mount=type=cache,target=/root/.m2 \     # BuildKit cache: Maven's downloads survive between builds
    chmod +x mvnw && ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS route-service  # stage 2a: a fresh, small base - only a JRE
RUN useradd --system --uid 1001 gps           # a non-root user...
USER gps                                      # ...and run as it (a container escape then isn't root)
COPY --from=build /workspace/gps-route-service/target/gps-route-service.jar gps-route-service.jar
EXPOSE 8090                                   # documentation only - publishing happens in compose "ports"
ENTRYPOINT ["java", "-jar", "/app/gps-route-service.jar"]

FROM eclipse-temurin:21-jre AS gps-service    # stage 2b, the LAST stage = the default target
...
```

What each idea buys us:

- **Multi-stage build:** the JDK, Maven, the `.m2` cache and our source code stay in the `build` stage and **never
  reach the final image**. The shipped image holds only a JRE and one jar. That makes it smaller and leaves less to
  attack.
- **Targets:** one Dockerfile builds two apps. Compose picks the app with `target: route-service`, and a plain
  `docker build .` builds the last stage, gps-service.
- **`ENTRYPOINT` vs `CMD`:** `ENTRYPOINT` is *the program*, and `CMD` is its *default arguments* (which you can
  replace at run time). We use the exec form `["java", ...]` and not the shell form `java ...`, so java runs as PID 1
  and receives `SIGTERM` directly. That means `docker stop` triggers Spring's graceful shutdown and doesn't end in a
  kill after 10 s.
- **[`.dockerignore`](../.dockerignore):** keeps `.env`, `local-secrets.properties`, `target/`, `docs/` and others
  out of the build context. That makes builds faster and means **secrets can't leak into an image layer.** That
  matters because anyone who has the image can read every layer.

### 4.2 [`gps-dashboard-ui/Dockerfile`](../gps-dashboard-ui/Dockerfile): the layer-cache trick

```dockerfile
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci     # this layer is reused while the lock file is unchanged
COPY next.config.ts tsconfig.json postcss.config.mjs ./
COPY src src                                        # code changes only invalidate from here down
RUN npm run build
```

Docker reuses a cached layer until one of its inputs changes, and **every layer after a changed one is rebuilt**. So
copy the rarely-changing files (the dependency list) first and the often-changing ones (`src`) last. Editing a
React component then doesn't re-download 300 MB of npm packages. (We hit the flip side of this in commit
`099e942`: `postcss.config.mjs` wasn't copied, so Tailwind silently didn't run inside the image. A Dockerfile copies
only what you tell it to.)

### 4.3 [`infrastructure/routing/graphhopper/Dockerfile`](../infrastructure/routing/graphhopper/Dockerfile): supply-chain safety

`ADD --checksum=sha256:8462f7… https://repo1.maven.org/.../graphhopper-web-11.1.jar` downloads the jar **and fails
the build if a single byte differs** from the hash we pinned. `VOLUME /data` marks where the 5 GB of map data
lives, and an entrypoint script (`start-graphhopper.sh`) decides at start-up whether to download, import or serve.

---

## 5. Our compose file, feature by feature

| Feature | Our line | What it does |
|---|---|---|
| Project name | `name: gps-streaming-platform` | Prefix for container, network and volume names. A second project (`-p gps-streams-verify`) can run beside it without touching it. |
| `image:` + `build:` together | gps-service | `build` says how to make it, and `image` says what to call it. With `--build` it builds locally; with `docker compose pull` it downloads that name. |
| Variables with defaults | `${GPS_PROCESSING_FLOW:-kafka}` | Read from the shell or from `.env`; uses `kafka` if unset. |
| Required variables | `${GPS_DATABASE_PASSWORD:?run infrastructure/docker/create-docker-env.ps1 first}` | Refuses to start with that message if the password isn't set. |
| `.env` file | git-ignored, written by `create-docker-env.ps1` | Compose reads it automatically. That keeps passwords out of git. |
| `restart: always` | every service | Docker starts the container again if it dies, and whenever Docker Desktop starts. A container you stop with `stop` stays stopped until the next Docker restart. |
| `healthcheck:` | postgres `pg_isready`, kafka `kafka-broker-api-versions.sh`, redis `ping`, dashboard `wget /live`, graphhopper `curl /health` | Docker runs this command periodically. `docker ps` shows `(healthy)`. `start_period: 3h` on graphhopper means "don't count failures during the import". |
| `depends_on` + `condition: service_healthy` | gps-service waits for postgres and redis | Start order **plus** readiness: gps-service isn't started until Postgres answers. A plain `depends_on` waits only for the dependency to *start*, not to be *ready*. |
| `required: false` | gps-service → kafka | "Wait for Kafka if it's part of this run." The direct flow (`up postgres redis` only) still works. |
| `profiles: ["routing"]` | graphhopper, route-service | Off by default. On with `--profile routing` or `COMPOSE_PROFILES=routing` in `.env`. Heavy optional parts stay out of a normal `up`. |
| Deliberately **no** `depends_on` | route-service | Starting the route service must never start a 1-hour map import. It reports "engine DOWN" until an engine answers. |
| Named volumes | the `volumes:` block at the bottom | Created once, kept by `down`, deleted only by `down -v`. |
| `extra_hosts` | `host.docker.internal:host-gateway` | Adds a hosts-file entry that points at the host machine. |
| Second compose file | [`infrastructure/home-server/docker-compose.yml`](../infrastructure/home-server/docker-compose.yml) | The HP runs a *different* file with `GH_IMPORT: never` and smaller heaps: same images, different wiring. |

---

## 6. The commands, and what each one actually does

Run all of these from the repo folder. The `docker compose` commands act only on the services in
`docker-compose.yml`.

### Day to day

```bash
docker compose up -d                       # create + start everything that isn't running (-d = in the background)
docker compose up -d --build               # rebuild our images from source first, then recreate changed containers
docker compose up -d --no-deps --build gps-service   # rebuild and replace ONLY gps-service, don't touch its dependencies
docker compose ps                          # status and health of this project's containers
docker compose logs -f gps-service         # follow one service's logs (stdout/stderr of PID 1)
docker compose stop                        # stop the containers, keep them (start brings them back as they were)
docker compose start                       # start stopped containers again
docker compose restart gps-service         # stop + start, same container, same config (does NOT pick up YAML edits)
docker compose down                        # stop AND remove containers + the network.  Volumes (data) are kept.
docker compose down -v                     # ... and delete the volumes.  This wipes all trips.  Think first.
```

The key distinction is that **`restart` reuses the container, while `up -d` recreates any container whose config
changed.** After you edit `docker-compose.yml` or `.env`, use `up -d`, because `restart` won't see the change.

### Looking inside

```bash
docker exec -it gps-streaming-platform-postgres-1 psql -U gps_app -d gps_tracking   # a shell/tool INSIDE a running container
docker exec gps-streaming-platform-gps-service-1 sh -c 'id; ps'                     # we ran this: uid=1001(gps), PID 1 = java
docker inspect gps-streaming-platform-gps-service-1        # full JSON: env, mounts, IP, restart policy, limits
docker stats                                               # live CPU / memory per container (like Task Manager)
docker history srmecotech/processing-layer:latest          # the layers of an image and their sizes
docker compose config                                      # the YAML after variables are filled in, which is the best debugging tool
```

### Images, disk and cleanup

```bash
docker images                     # local images
docker build -t name ./folder     # build one image without compose
docker pull postgres:14.22        # download from a registry
docker push srmecotech/processing-layer:latest   # upload (needs docker login)
docker system df                  # where Docker's disk goes: images / containers / volumes / build cache
docker builder prune              # delete the build cache (we freed 15 GB this way today)
docker image prune                # delete unused, untagged images
docker volume ls / docker volume rm <name>       # volumes are only deleted on purpose
```

Today's `docker system df`: images 5.4 GB, volumes 4.5 GB, build cache 0 B (after the cleanup).

### Moving data between machines (we wrote this for the HP)

A volume isn't a file you can copy directly, so you **run a throwaway container that mounts it and tars it**:

```bash
docker run --rm -v gps-streaming-platform_gps-routing-data:/data -v "$PWD:/out" alpine:3.20 \
    tar -C /data/graph-cache -cf /out/asia-india-graph.tar asia-india
```

That's exactly what [`export-routing-graph.ps1`](../infrastructure/home-server/export-routing-graph.ps1) and
[`install-routing-graph.sh`](../infrastructure/home-server/install-routing-graph.sh) do. `--rm` deletes the helper
container when it exits. The same pattern backs up Postgres.

---

## 7. Real incidents on this project (practical lessons, not theory)

| What happened | The Docker lesson |
|---|---|
| **The C: drive filled up.** `docker_data.vhdx` had grown to 36.1 GB. After deleting images and cache, the file stayed 36.1 GB. Running `docker desktop stop` → `wsl --shutdown` → diskpart `compact vdisk` brought it to 13.8 GB. | On Windows and Mac, Docker's storage is a VM disk that **grows but never shrinks by itself.** Prune first, then compact. |
| **800 GB of writes in 40 minutes** while GraphHopper prepared the India graph with memory-mapped files. We switched the import to RAM, and the writes stopped. | Containers share the host's disk and page cache. The isolation doesn't cover I/O unless you set limits (`blkio`), so one container can wear a whole SSD. |
| **Docker Desktop wouldn't start** because a stale `engine.sock` file from the secrets engine was in the way. Renaming that folder fixed it, with no factory reset (which would have deleted every volume). | Know where Docker keeps its state before you press "reset". A factory reset = losing all volumes = losing the database. |
| **A container exited with code 137.** That's 128 + 9, meaning it got SIGKILL. The usual suspect is out-of-memory, but this time Docker Desktop's log showed the project was stopped by hand. | Exit 137 means killed. Check `docker inspect` → `State.OOMKilled` and the Docker Desktop log before blaming memory. |
| **A verification stack came back by itself** after it was stopped. | `restart: always` brings back containers you only *stopped* when Docker restarts. Use `down` to remove them for good. |
| **A shell script failed with `\r: not found`** in the container. | Windows line endings (CRLF) break Linux scripts. Our [`.gitattributes`](../.gitattributes) forces `*.sh text eol=lf`. |
| **`du /data/maps` became `du C:/Program Files/Git/data/maps`** in Git Bash. | Git Bash rewrites Unix-looking paths. Use `MSYS_NO_PATHCONV=1 docker exec ...` or run from PowerShell. |
| **A test container used an old image** and started downloading 1.6 GB. | `docker run` uses whatever image the tag points to *locally*. Rebuild (`--build`) or pull before testing a change. |
| **Tailwind missing from the dashboard image** while it worked locally. | The image contains only what the Dockerfile copies. "Works in `npm run dev`, broken in Docker" usually means a missing `COPY` or a `.dockerignore` rule. |

---

## 8. What we could have implemented (gaps and upgrades, in priority order)

These are honest gaps in **our** setup, each with the exact change.

1. **Memory and CPU limits.** *Memory limits now added to every service in `docker-compose.yml` (gps-service 2g,
   Kafka 1.5g, Postgres and Redis 1g, kafka-ui and route-service 768m, dashboard 512m, GraphHopper 10g for the
   import); they take effect the next time each container is recreated.* Before that, `mem=0` meant any container
   could take the whole 12 GB VM. The shape is:
   ```yaml
   deploy:
     resources:
       limits: { memory: 1g, cpus: "2" }
   ```
   For Java, also set `-XX:MaxRAMPercentage=75` so the JVM sizes its heap to the limit. This is the first thing the
   HP with 8 GB needs.
2. **Health checks on gps-service, kafka-ui and route-service.** *gps-service now has one (below).* Before, it had none, so `docker ps` says
   "Up" even when Spring is broken, and the dashboard can't wait for it to be *ready*. Spring Actuator already
   exists, and the Temurin JRE image already ships `curl`, so this is one block:
   `test: ["CMD", "curl", "-fs", "http://localhost:8080/actuator/health"]`.
3. **Image tags you can trace.** `:latest` doesn't tell you which code is running. Tag with the git commit
   (`srmecotech/processing-layer:099e942`) and keep `latest` as an alias, so rollback becomes
   `GPS_SERVICE_IMAGE=...:<old-sha> docker compose up -d`.
4. **CI that builds, scans and pushes.** A GitHub Actions workflow: `docker buildx build` → scan with Trivy or
   `docker scout cves` → push to Docker Hub or GHCR on every merge to main. Then the HP only ever *pulls* and never
   builds.
5. **Log rotation.** Container logs grow forever by default. Use `logging: { driver: local, options: { max-size: 20m,
   max-file: "5" } }`, or a central log stack (Loki + Grafana).
6. **Secrets as files, not environment variables.** Anything with `docker inspect` access can read environment
   variables. Compose `secrets:` mounts them as files at `/run/secrets/...`, and Spring can read them with
   `configtree:`.
7. **Faster Java rebuilds with layered jars.** Each code change today ships a new 89.9 MB layer. Spring Boot's
   `java -Djarmode=tools -jar app.jar extract --layers` splits dependencies (rarely changing) from our classes
   (often changing), so a rebuild pushes a few hundred KB. Alternatives are Google's **Jib** (builds images from
   Maven without a Dockerfile) and Paketo **buildpacks** (`mvn spring-boot:build-image`).
8. **Smaller and harder images.** Use `read_only: true` + `tmpfs: /tmp`, `cap_drop: [ALL]` and
   `security_opt: [no-new-privileges:true]`. Consider a distroless or `jlink`-trimmed runtime (Google's
   `gcr.io/distroless/java21`).
9. **Backups for the Postgres volume.** Run a nightly `docker exec postgres pg_dump ... > backup.sql` from a
   scheduled task, or add a backup sidecar container. Right now `down -v` or a disk failure loses everything.
10. **Dev loop with `docker compose watch`** (`develop.watch` in the YAML): sync source changes into a running
    container or rebuild automatically.
11. **Separate networks.** Put Postgres, Redis and Kafka on a `backend` network that the dashboard isn't on. Only the
    services that need a database can then reach it.
12. **Pin images by digest** (`postgres:14.22@sha256:…`) so a re-pushed tag can't change under us.
13. **Compose override files:** `docker-compose.override.yml` for dev (ports, debug) vs `-f docker-compose.prod.yml`
    (limits, no published database ports).

**When Compose stops being enough:** Compose runs containers on **one machine**. Once you need several machines,
automatic failover, rolling updates with zero downtime, or autoscaling, that's **Kubernetes** (GKE at Google, AKS at
Microsoft, EKS at Amazon). Our compose file maps onto it almost line by line:

| Compose | Kubernetes |
|---|---|
| a service | Deployment (+ ReplicaSet + Pods) |
| `ports:` | Service (ClusterIP / LoadBalancer) + Ingress |
| named volume | PersistentVolumeClaim |
| `environment:` / `.env` | ConfigMap / Secret |
| `healthcheck` | liveness / readiness / startup probes (`start_period: 3h` → startupProbe) |
| `restart: always` | the controller always keeps N replicas running |
| `depends_on` | doesn't exist; apps must retry, and readiness probes gate the traffic |
| `profiles` | separate manifests or Helm values |
| `deploy.resources.limits` | `resources.requests` / `limits` |

Kafka and Postgres on Kubernetes usually use operators (Strimzi, CloudNativePG) or managed services (Confluent
Cloud, Cloud SQL, Azure Database for PostgreSQL).

---

## 9. The debate: questions you'll be asked, and crisp answers

**"Isn't Docker just a lightweight VM?"**
No. A VM virtualises hardware and boots its own kernel. A container is a host process isolated by namespaces and
limited by cgroups, sharing the host kernel. In ours, `java` is PID 1 and there's no kernel inside. On Windows,
Docker Desktop runs *one* Linux VM that hosts all the containers.

**"Kubernetes dropped Docker, so Docker is dead."**
Kubernetes removed *dockershim*, its adapter for talking to the Docker engine, in v1.24. It runs containers through
containerd or CRI-O directly. **Images built with Docker are OCI images and run on Kubernetes unchanged.** Docker
remains the most common tool for *building* and for local development, and containerd itself came out of Docker.

**"Why Compose and not Kubernetes?"**
It's the right size for the job. We're one machine (a laptop, then the HP), with no need for failover or autoscaling.
Compose costs one YAML file. Kubernetes adds a control plane, manifests and ingress. The move is mechanical when we
need it (see the table in section 8).

**"Are containers secure?"**
They're less isolated than VMs because the kernel is shared. Defences: run as non-root (**we do**, uid 1001), keep
images small with multi-stage builds (**we do**), keep secrets out of images with `.dockerignore` and `.env`
(**we do**), verify downloads with checksums (**we do**), then drop capabilities, use a read-only rootfs, scan
images and enforce seccomp/AppArmor (gaps 4 and 8). For hostile multi-tenant workloads, use sandboxed runtimes:
Google's **gVisor** (which runs Cloud Run) or **Kata Containers** (lightweight VMs per pod).

**"Why multi-stage builds?"**
The build needs a JDK, Maven and the source; the runtime needs a JRE and a jar. Two stages mean the shipped image
holds only the second set. That makes it smaller, faster to pull, and gives an attacker less to work with.

**"Why is your database in a container? Isn't that a bad idea?"**
For dev and a home server it's fine, *because the data is in a named volume, not in the container.* Containers are
disposable and volumes aren't. In production we'd use a managed database or an operator, plus backups (gap 9).

**"How do containers find each other?"**
Compose creates a network, and Docker's embedded DNS resolves service names. gps-service connects to `postgres:5432`
with no IP addresses. Published ports exist only for access from outside.

**"`depends_on` makes startup safe, right?"**
Only with `condition: service_healthy` and a real health check, which is what we use for Postgres and Redis. Even
then, apps should retry, because a dependency can restart later. Kubernetes has no `depends_on` at all for this
reason.

**"Docker vs Podman?"**
Podman is daemonless and rootless by default, Red Hat's take on it. It uses the same OCI images, supports
`podman compose`, and is mostly CLI-compatible. Docker has the bigger ecosystem and Desktop tooling. It's a choice
of tooling, not of architecture.

**"What do Google and Microsoft actually run?"**
Google ran containers internally long before Docker, on **Borg**; cgroups came from Google engineers. Kubernetes is
Borg's open-source descendant. Google Cloud offers GKE (Kubernetes), Cloud Run (serverless containers: you give it
an image, it scales from 0) and Artifact Registry, and contributes Jib, distroless, Kaniko (in-cluster builds) and
gVisor. Microsoft offers AKS, Azure Container Apps, Azure Container Instances and ACR. It also built WSL2, which
Docker Desktop on Windows runs on, and Windows containers. Our image would run unchanged on Cloud Run or Azure
Container Apps.

**"What's the difference between `stop`, `down` and `down -v`?"**
`stop` pauses the containers (same containers, restartable). `down` removes the containers and the network but keeps
the volumes. `down -v` also removes the volumes, which means the data is gone.

---

## 10. Cheat sheet

```text
image      = read-only template (layers)            container = a running image (a process + a writable layer)
Dockerfile = how to build an image                   compose   = how to run many containers together (YAML)
volume     = data that survives containers           network   = containers reach each other by service name
engine     = dockerd → containerd → runc             Desktop   = Linux VM (WSL2) + engine + GUI on Windows
registry   = where images live (Docker Hub, GAR, ACR) OCI      = the open standard every tool shares

up -d --build      build + start / update            ps / logs -f / exec -it / stats / inspect   look
restart            same container, same config       up -d after editing YAML or .env
stop → start       pause, keep                       down = remove (keeps data)   down -v = DELETE data
system df          disk use                          builder prune / image prune    clean
--profile routing  turn on optional services         compose config   see the final YAML
exit 137 = killed (OOM or stopped)                   exit 143 = SIGTERM (normal stop)
```

Our ports: dashboard 3000, gps-service 8080, kafka-ui 8085, RedisInsight 8001, Postgres 5433, Redis 6380,
Kafka 29092, GraphHopper 8989, route-service 8090.
