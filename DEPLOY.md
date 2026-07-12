# Deploying Pulse to Oracle Cloud (Always Free ARM)

This runbook stands the full stack up on a single Oracle Cloud Always Free
ARM instance, reachable at `http://<PUBLIC_IP>` over plain HTTP (no domain, no
HTTPS). Everything runs from `docker-compose.prod.yml`; only port 80 is
exposed.

Accepted trade-offs for this deployment: ARM (aarch64) architecture, plain
HTTP over an IP address (no TLS), and a single free instance that Oracle may
reclaim or refuse to create when free capacity is exhausted.

All container images used here are multi-architecture and run natively on
aarch64 — including `timescale/timescaledb:latest-pg16`, verified before
writing this guide.

---

## 0. What you need before starting

- An Oracle Cloud account (free signup; a credit card is required for identity
  verification but the Always Free resources are not charged).
- An SSH key pair on your Windows machine (created in step 2).
- ~30–60 minutes, plus a one-time ~40-minute wait for forecast warm-up.

---

## 1. Create the Always Free ARM instance

1. In the Oracle Cloud console: **Menu → Compute → Instances → Create instance**.
2. **Name**: `pulse`.
3. **Image and shape → Edit**:
   - **Image**: Canonical **Ubuntu 22.04** (make sure it is the **aarch64/ARM**
     build — the shape selection below forces this).
   - **Shape → Change shape → Ampere → `VM.Standard.A1.Flex`**.
   - Set **2 OCPUs** and **12 GB** memory. (Always Free allows up to 4 OCPU /
     24 GB total across ARM instances; 2/12 is plenty for Pulse and leaves
     headroom.)
4. **Networking**: leave the default — it creates a new VCN and public subnet
   and **assigns a public IPv4**. Confirm "Assign a public IPv4 address" is on.
5. **Add SSH keys**: choose **Paste public keys** and paste the contents of
   your `pulse_key.pub` from step 2 (create the key first if you have not).
6. **Boot volume**: default (~47 GB) is fine.
7. Click **Create**.

### If you get "Out of capacity" / "Out of host capacity"

This is common for free ARM. Options, in order:

- Change the **Availability Domain** (AD-1, AD-2, AD-3) in the create dialog and
  retry — capacity differs per AD.
- Retry every few minutes; free ARM capacity frees up intermittently. Simply
  clicking **Create** again often works within an hour or two.
- Temporarily reduce to **1 OCPU / 6 GB** — smaller requests are likelier to
  fit. Pulse still runs (measured usage is well under 1 GB); you can resize up
  later if capacity appears.
- Do **not** switch to a paid shape by accident — keep the shape on
  `VM.Standard.A1.Flex` so it stays Always Free.

Once created, note the instance's **Public IP address** (Instance details page).
It is referred to below as `<PUBLIC_IP>`.

---

## 2. Create an SSH key (Windows) and connect

In a PowerShell window on your machine:

```powershell
# Create a key pair (press Enter through the prompts; a passphrase is optional).
ssh-keygen -t ed25519 -f $HOME\.ssh\pulse_key

# Show the PUBLIC key — copy this whole line into Oracle's "Paste public keys".
Get-Content $HOME\.ssh\pulse_key.pub
```

Create the instance (step 1) with that public key, then connect:

```powershell
ssh -i $HOME\.ssh\pulse_key ubuntu@<PUBLIC_IP>
```

The default username for Oracle's Ubuntu image is `ubuntu`. Accept the host
fingerprint the first time.

---

## 3. Open port 80 — BOTH firewall layers

Oracle blocks inbound traffic in **two** independent places. Port 80 must be
opened in **both**, or the dashboard will be unreachable even though the
containers are healthy. This is the most commonly missed step.

### 3a. Cloud firewall — Security List

1. Console: **Networking → Virtual Cloud Networks → (your VCN) → Subnets →
   (the public subnet) → Security Lists → (default security list)**.
2. **Add Ingress Rules**:
   - **Source Type**: CIDR
   - **Source CIDR**: `0.0.0.0/0`
   - **IP Protocol**: TCP
   - **Destination Port Range**: `80`
   - Save. (Port 22 for SSH is already open by default.)

### 3b. Host firewall — iptables on the instance

Oracle's Ubuntu image ships with iptables rules that reject everything except
SSH. Add a rule for port 80 and persist it (run on the instance over SSH):

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo netfilter-persistent save
```

---

## 4. Install Docker

On the instance:

```bash
# Docker Engine + compose plugin (the convenience script supports arm64).
curl -fsSL https://get.docker.com | sudo sh

# Run docker without sudo (log out and back in afterwards for this to apply).
sudo usermod -aG docker $USER

# Verify (reconnect first if the group change hasn't taken effect):
docker version
docker compose version
```

Reconnect (`exit`, then `ssh ...` again) so the `docker` group membership
applies.

---

## 5. Get the code and set the password

```bash
# Install git if needed, then clone.
sudo apt-get update && sudo apt-get install -y git
git clone https://github.com/YusufKosarDev/pulse.git
cd pulse

# Create the production env file from the template.
cp .env.prod.example .env.prod

# Generate a strong DB password and put it in .env.prod.
openssl rand -base64 24
# Copy the output, then edit .env.prod and replace __CHANGE_ME__ with it:
nano .env.prod
#   (in nano: edit, then Ctrl-O Enter to save, Ctrl-X to exit)
```

`.env.prod` is gitignored — the real password never leaves the server.

---

## 6. Build and start the stack

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
```

The first build compiles the Java service and the frontend on ARM; expect a
few minutes. Then check everything is healthy:

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml ps
```

All services should show `running`, and the health-checked ones `(healthy)`.

---

## 7. Verify

From your own machine, open:

```
http://<PUBLIC_IP>
```

The dashboard should load, the live chart should start moving within a few
seconds, and anomalies should appear in the alert panel over time.

If it does not load:
- Re-check **both** firewall layers (step 3) — this is the usual cause.
- On the instance, confirm the frontend is up locally:
  `curl -I http://localhost` should return `HTTP/1.1 200`.
- Check logs: `docker compose --env-file .env.prod -f docker-compose.prod.yml logs frontend ingest-service`.

---

## 8. Forecast warm-up (~40 minutes)

The forecaster needs roughly two 10-minute seasons of history before it emits
predictions, and graded forecast outcomes accumulate only as real crossings
happen. Right after the first deploy:

- The live chart and anomaly detection work **immediately**.
- The dashed **forecast line** and the **forecast scorecard** appear after the
  model has seen enough data — **wait ~40 minutes** before expecting them.

Because the stack runs continuously (the simulator streams 24/7 and the
forecaster warm-starts from the database on restart), this warm-up is a
one-time cost at first deploy, not something that repeats before each demo.
**Deploy at least a day before the presentation** so the system is fully warm
and a few forecast outcomes have been graded.

---

## Security notes (what this setup does and does not protect)

- **Only ports 22 (SSH) and 80 (HTTP) are reachable.** Redis, TimescaleDB,
  ingest-service and ml-service publish no host ports — they are reachable only
  on the internal Docker network.
- **SSH is key-only.** Oracle's Ubuntu image disables password login by
  default; keep it that way. Do not add a password to the `ubuntu` account.
- **The DB password is strong and server-only** (step 5), never the local
  `pulse/pulse` development value, and never committed.
- **No HTTPS.** Traffic to `http://<PUBLIC_IP>` is unencrypted. Acceptable for a
  read-only demo dashboard with no login and no sensitive data. If a domain is
  added later, put Caddy or nginx TLS in front and switch the dashboard to
  `https://`.

---

## Useful operations

```bash
# From the pulse/ directory on the instance:
alias dc='docker compose --env-file .env.prod -f docker-compose.prod.yml'

dc ps                 # status
dc logs -f ingest-service   # follow a service's logs
dc restart ml-service       # restart one service
dc down                     # stop everything (keeps the DB volume)
dc up -d --build            # rebuild and restart after pulling new code

# Update to the latest code:
git pull && dc up -d --build
```
