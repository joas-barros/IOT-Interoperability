"""
twins_router.py — Endpoints de Digital Twins versionados em /api/v1/twins.

Endpoints:
  GET /api/v1/twins              → todos os twins (viewer + admin)
  GET /api/v1/twins/{device_id}  → twin específico (viewer + admin)
"""

from fastapi import APIRouter, Depends, HTTPException

from api.auth.jwt_handler import UserContext, get_current_user

router = APIRouter(prefix="/api/v1/twins", tags=["Digital Twins"])


def get_twin_store():
    from api.main import twin_store
    return twin_store


@router.get("", summary="Retorna todos os Digital Twins")
async def get_twins(
    _user: UserContext = Depends(get_current_user),
):
    """
    Retorna o estado atual de todos os Digital Twins (drones e estações).
    Requer autenticação (perfil viewer ou admin).
    """
    store = get_twin_store()
    return await store.get_all()


@router.get("/{device_id}", summary="Retorna o twin de um dispositivo específico")
async def get_twin(
    device_id: str,
    _user: UserContext = Depends(get_current_user),
):
    """
    Retorna o Digital Twin de um dispositivo pelo seu ID.
    Requer autenticação (perfil viewer ou admin).
    """
    store   = get_twin_store()
    drone   = await store.get_drone(device_id)
    station = await store.get_station(device_id)
    twin    = drone or station

    if not twin:
        raise HTTPException(
            status_code=404,
            detail=f"Dispositivo '{device_id}' não encontrado.",
        )
    return twin.model_dump(mode="json")