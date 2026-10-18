"""Integración real del contenedor Report. Usar solo en entorno local de evaluación.

Publica exclusivamente datos sintéticos; exige colas Report vacías y sin otros
consumidores. No elimina colas, volúmenes ni mensajes ajenos. Requiere Python 3.
"""
import argparse
import base64
import copy
import json
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "evidencias-locales"


def docker(*args, check=True):
    result = subprocess.run(["docker", *args], cwd=ROOT, capture_output=True, text=True, timeout=300)
    if check and result.returncode:
        raise RuntimeError("Falló Docker: " + " ".join(args[:3]) + " (revisar contenedor/logs)")
    return result


def esperar(condicion, mensaje, timeout=45):
    limite = time.monotonic() + timeout
    while time.monotonic() < limite:
        resultado = condicion()
        if resultado:
            return resultado
        time.sleep(0.5)
    raise RuntimeError("Tiempo agotado: " + mensaje)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--infra", type=Path, default=ROOT.parent / "digitalfix-infra")
    parser.add_argument("--crear-usuario", action="store_true", help="Crea cuenta propia si no existe; nunca cambia claves existentes")
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    cfg = json.loads(docker("compose", "--env-file", str(args.infra/"mq/.env"), "-f",
                            str(args.infra/"mq/compose.yml"), "config", "--format", "json").stdout)
    env = cfg["services"]["rabbitmq1"]["environment"]
    auth = base64.b64encode((env["RABBITMQ_DEFAULT_USER"]+":"+env["RABBITMQ_DEFAULT_PASS"]).encode()).decode()

    def api(method, path, body=None):
        req = urllib.request.Request("http://127.0.0.1:15672/api/"+path,
            data=None if body is None else json.dumps(body).encode(), method=method,
            headers={"Authorization":"Basic "+auth, "Content-Type":"application/json"})
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read()
            return json.loads(raw) if raw else None

    if args.crear_usuario:
        try:
            api("GET", "users/digitalfix_report")
            existe = True
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            existe = False
        if not existe:
            if (ROOT/".env").exists():
                raise RuntimeError("Existe .env: no se reemplazarán credenciales locales")
            clave = secrets.token_urlsafe(32)
            api("PUT", "users/digitalfix_report", {"password":clave,"tags":[]})
            recursos = r"^(cmd\.(direct|topic|dead\.dlx)|q\.cmd\.report(\.dlq)?)$"
            api("PUT", "permissions/digitalfix/digitalfix_report", {
                "configure":recursos, "write":r"^(cmd\.dead\.dlx|q\.cmd\.report(\.dlq)?)$", "read":recursos})
            (ROOT/".env").write_text("RABBITMQ_USERNAME=digitalfix_report\nRABBITMQ_PASSWORD="+clave+"\n",encoding="utf-8")
            print("OK: usuario propio creado sin etiquetas administrativas", flush=True)
    if not (ROOT/".env").exists():
        raise RuntimeError("Configura .env o utiliza --crear-usuario en un entorno nuevo")

    def cola(nombre):
        try:
            return api("GET", "queues/digitalfix/"+nombre)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise

    for nombre in ("q.cmd.report", "q.cmd.report.dlq"):
        q = cola(nombre)
        if q and (q.get("messages",0) or q.get("consumers",0)):
            raise RuntimeError("La prueba exige colas Report vacías y sin consumidores: " + nombre)

    def snapshot_notify():
        nombres = ("q.cmd.email", "q.cmd.dispatch")
        colas = [{k:q.get(k) for k in ("name","durable","auto_delete","arguments","node")}
                 for q in api("GET","queues/digitalfix") if q["name"].startswith(nombres)]
        enlaces = [b for b in api("GET","bindings/digitalfix") if b["destination"].startswith(nombres)]
        exchanges = [{k:e.get(k) for k in ("name","type","durable","auto_delete","arguments")}
                     for e in api("GET","exchanges/digitalfix") if e["name"].startswith("cmd.")]
        return {"colas":sorted(colas,key=lambda q:q["name"]),
                "enlaces":sorted(enlaces,key=lambda b:json.dumps(b,sort_keys=True)),
                "exchanges":sorted(exchanges,key=lambda e:e["name"])}

    antes = snapshot_notify()
    resultados = []
    def ok(texto):
        resultados.append(texto)
        print("OK: "+texto, flush=True)

    def listo():
        q = cola("q.cmd.report")
        return q and q.get("consumers") == 1

    def exec_report(*args, root=False, check=True):
        return docker("compose","exec","-T",*( ["--user","root"] if root else [] ),"report",*args,check=check)

    def archivo(evento, extension="ok"):
        return "/data/"+evento["eventId"]+"."+extension

    def completo(evento):
        return exec_report("test","-f",archivo(evento),check=False).returncode == 0

    def logs():
        return docker("compose","logs","--no-color","report").stdout

    def evento(largo=False):
        return {"schemaVersion":"1.0", "type":"GENERAR_REPORTE", "eventId":str(uuid.uuid4()),
            "timestamp":"2026-10-17T18:00:00Z", "traceId":str(uuid.uuid4()), "correlationId":"15",
            "payload":{"ordenId":15,"estado":"CERRADA", "solicitanteId":"oid-sintetico-solicitante",
                "tecnicoId":"tecnico-prueba", "servicioId":2,
                "descripcion":"Revisión y reparación eléctrica en Ñuñoa. " * (20 if largo else 1),
                "direccion":"Dirección sintética, Valparaíso, Chile", "fechaCreacion":"2026-10-15T12:00:00Z",
                "fechaCierre":"2026-10-17T18:00:00Z",
                "repuestos":[{"repuestoId":i,"cantidad":2} for i in range(1,121 if largo else 3)]}}

    def publicar(datos, exchange="cmd.direct", key="report.gen", message_id=None):
        cuerpo = datos if isinstance(datos,str) else json.dumps(datos,ensure_ascii=False)
        result = api("POST", "exchanges/digitalfix/"+exchange+"/publish", {
            "routing_key":key, "payload":cuerpo, "payload_encoding":"string",
            "properties":{"delivery_mode":2,"content_type":"application/json",
                          "message_id":message_id or datos["eventId"]}})
        if not result["routed"]:
            raise RuntimeError("Mensaje de prueba sin ruta")

    def ver_dlq(message_id, quitar=False):
        mensajes = api("POST","queues/digitalfix/q.cmd.report.dlq/get", {
            "count":1,"ackmode":"ack_requeue_true","encoding":"auto"})
        if not mensajes:
            return None
        m = mensajes[0]
        if m["properties"].get("message_id") != message_id:
            raise RuntimeError("DLQ contiene mensaje ajeno; se dejó reencolado")
        muertes = m["properties"].get("headers",{}).get("x-death",[])
        if not any(d.get("reason")=="rejected" and d.get("queue")=="q.cmd.report" for d in muertes):
            raise RuntimeError("No hay evidencia x-death rejected")
        if quitar:
            # Solo entorno exclusivo ya comprobado: ACK del mensaje sintético identificado.
            borrado = api("POST","queues/digitalfix/q.cmd.report.dlq/get", {
                "count":1,"ackmode":"ack_requeue_false","encoding":"auto"})
            if len(borrado)!=1 or borrado[0]["properties"].get("message_id")!=message_id:
                raise RuntimeError("La DLQ cambió durante la prueba; detener otros operadores")
        return m

    permisos_alterados = False
    try:
        docker("compose","up","-d","--build")
        esperar(listo,"listener Report activo")
        q = cola("q.cmd.report"); dlq = cola("q.cmd.report.dlq")
        assert q["durable"] and not q["auto_delete"] and q["type"] == "classic"
        assert q["arguments"] == {"x-queue-type":"classic","x-dead-letter-exchange":"cmd.dead.dlx","x-dead-letter-routing-key":"report.dead"}
        assert dlq["durable"] and dlq["type"] == "classic" and not dlq["auto_delete"]
        ok("Topología classic durable y listener real activos")

        directo = evento(); publicar(directo); esperar(lambda:completo(directo),"PDF direct")
        original = exec_report("sha256sum",archivo(directo,"pdf")).stdout
        fecha = exec_report("stat","-c","%y",archivo(directo,"pdf")).stdout
        publicar(directo)
        esperar(lambda:any(directo["eventId"] in l and "duplicado_completado" in l for l in logs().splitlines()),"deduplicación")
        assert exec_report("sha256sum",archivo(directo,"pdf")).stdout == original
        assert exec_report("stat","-c","%y",archivo(directo,"pdf")).stdout == fecha
        ok("Direct report.gen, PDF persistido, ACK y duplicado sin reescritura")

        topic = evento(largo=True); publicar(topic,"cmd.topic","report.prueba")
        esperar(lambda:completo(topic),"PDF topic")
        cid = docker("compose","ps","-q","report").stdout.strip()
        docker("cp",cid+":"+archivo(topic,"pdf"),str(OUT/"informe-largo.pdf"))
        (OUT/"evento-largo.json").write_text(json.dumps(topic,indent=2,ensure_ascii=False),encoding="utf-8")
        ok("Topic report.*, PDF largo extraído para inspección")

        docker("compose","up","-d","--force-recreate")
        esperar(listo,"listener tras recreación")
        assert completo(directo)
        publicar(directo)
        esperar(lambda:any(directo["eventId"] in l and "duplicado_completado" in l for l in logs().splitlines()),"deduplicación tras recreación")
        assert exec_report("sha256sum",archivo(directo,"pdf")).stdout == original
        assert exec_report("stat","-c","%y",archivo(directo,"pdf")).stdout == fecha
        ok("Persistencia y deduplicación tras recrear contenedor conservando volumen")

        invalido = str(uuid.uuid4()); publicar("{json-invalido","cmd.direct","report.gen",invalido)
        esperar(lambda:ver_dlq(invalido),"JSON inválido DLQ"); ver_dlq(invalido,quitar=True)
        estado = evento(); estado["payload"]["estado"]="ASIGNADA"; publicar(estado)
        esperar(lambda:ver_dlq(estado["eventId"]),"estado inválido DLQ"); ver_dlq(estado["eventId"],quitar=True)
        conflicto = copy.deepcopy(directo); conflicto["payload"]["descripcion"]="Contenido diferente"; publicar(conflicto)
        esperar(lambda:ver_dlq(conflicto["eventId"]),"identidad conflictiva DLQ"); ver_dlq(conflicto["eventId"],quitar=True)
        assert exec_report("sha256sum",archivo(directo,"pdf")).stdout == original
        ok("JSON inválido, estado incorrecto e identidad conflictiva: NACK y x-death rejected en DLQ")

        exec_report("chmod","500","/data",root=True); permisos_alterados = True
        recuperable = evento(); publicar(recuperable)
        esperar(lambda:any(recuperable["eventId"] in l and "intento=1" in l and "almacenamiento_" in l for l in logs().splitlines()),"primer fallo temporal")
        exec_report("chmod","700","/data",root=True); permisos_alterados = False
        esperar(lambda:completo(recuperable),"recuperación de almacenamiento")
        ok("Fallo temporal real de escritura recuperado mediante reintento")

        exec_report("chmod","500","/data",root=True); permisos_alterados = True
        agotado = evento(); inicio = time.monotonic(); publicar(agotado)
        m = esperar(lambda:ver_dlq(agotado["eventId"]),"reintentos agotados DLQ")
        assert time.monotonic()-inicio >= 7
        trazas = [l for l in logs().splitlines() if agotado["eventId"] in l]
        for intento in (1,2,3):
            assert any("intento="+str(intento) in l and "almacenamiento_" in l for l in trazas)
        assert not completo(agotado)
        exec_report("chmod","700","/data",root=True); permisos_alterados = False
        # Guardar primero una copia local, publicar sin cambiar eventId y esperar éxito.
        (OUT/"reproceso.json").write_text(m["payload"],encoding="utf-8")
        publicar(json.loads(m["payload"]))
        esperar(lambda:completo(agotado),"reproceso controlado")
        ver_dlq(agotado["eventId"],quitar=True)
        ok("Tres intentos reales con esperas 2/5 s, DLQ y reproceso exitoso conservando eventId")

        esperar(lambda:cola("q.cmd.report").get("messages",-1)==0,"sin entregas pendientes")
        assert snapshot_notify()==antes
        ok("Cola principal sin mensajes pendientes; topología Notify intacta")
        (OUT/"resultados.json").write_text(json.dumps(resultados,indent=2,ensure_ascii=False),encoding="utf-8")
    finally:
        if permisos_alterados:
            exec_report("chmod","700","/data",root=True)
        (OUT/"report.log").write_text(logs(),encoding="utf-8")
        if snapshot_notify()!=antes:
            raise RuntimeError("La topología de Notify cambió durante la prueba")


if __name__ == "__main__":
    main()
