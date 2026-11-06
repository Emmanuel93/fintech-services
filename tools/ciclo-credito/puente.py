#!/usr/bin/env python3
"""A port bridge into the compose network, so the engine talks to every service from the Mac.

Only the gateway and closing publish host ports; everything else listens on 8080 inside the network.
Instead of `docker exec … wget` per call (slow, and no HTTP semantics), one small socat container joins
the network and forwards a host port to each service. It is a test tool: nothing in the stack changes.

    python3 puente.py up | down | status
"""
import subprocess
import sys

RED = "fintech-services_fintech-network"
NOMBRE = "ciclo-credito-puente"
IMAGEN = "alpine/socat:1.8.0.0"

# host port → (service, container port)
PUERTOS = {
    18082: ("party-service", 8080),
    18084: ("scoring-service", 8080),
    18085: ("credit-product-service", 8080),
    18086: ("origination-service", 8080),
    18087: ("credit-portfolio-service", 8080),
    18088: ("charges-service", 8080),
    18089: ("payments-service", 8080),
    18091: ("wallet-service", 8080),
    18092: ("beneficiary-service", 8080),
    18093: ("collections-service", 8080),
    18094: ("risk-service", 8080),
    18095: ("accounting-service", 8080),
    18096: ("commission-service", 8080),
    18097: ("invoicing-service", 8080),
    18100: ("compliance-service", 8080),
    18101: ("stp-service", 8080),
    18103: ("closing-service", 8080),
    18104: ("banking-service", 8080),
    18105: ("disbursement-service", 8080),
    18106: ("statement-service", 8080),
}

URL = {servicio: f"http://localhost:{puerto}" for puerto, (servicio, _) in PUERTOS.items()}


def up():
    down(quiet=True)
    forwards = " & ".join(f"socat TCP-LISTEN:{p},fork,reuseaddr TCP:{s}:{cp}" for p, (s, cp) in PUERTOS.items())
    cmd = ["docker", "run", "-d", "--name", NOMBRE, "--network", RED]
    for p in PUERTOS:
        cmd += ["-p", f"127.0.0.1:{p}:{p}"]
    cmd += ["--entrypoint", "sh", IMAGEN, "-c", f"{forwards} & wait"]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
    print(f"puente arriba: {len(PUERTOS)} puertos → {RED}")


def down(quiet=False):
    r = subprocess.run(["docker", "rm", "-f", NOMBRE], capture_output=True, text=True)
    if not quiet:
        print("puente abajo" if r.returncode == 0 else "no había puente")


def status():
    r = subprocess.run(["docker", "ps", "--filter", f"name={NOMBRE}", "--format", "{{.Status}}"],
                       capture_output=True, text=True)
    print(r.stdout.strip() or "sin puente")


if __name__ == "__main__":
    {"up": up, "down": down, "status": status}.get(sys.argv[1] if len(sys.argv) > 1 else "", status)()
