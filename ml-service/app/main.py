from fastapi import FastAPI

app = FastAPI(title="Pulse ML Service", version="0.1.0")


@app.get("/health")
def health():
    return {"status": "UP"}
