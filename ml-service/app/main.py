import logging
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import consumer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

_stop_event = threading.Event()


@asynccontextmanager
async def lifespan(app: FastAPI):
    worker = threading.Thread(target=consumer.run, args=(_stop_event,),
                              name="metric-consumer", daemon=True)
    worker.start()
    yield
    _stop_event.set()
    worker.join(timeout=5)


app = FastAPI(title="Pulse ML Service", version="0.2.0", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "UP"}
