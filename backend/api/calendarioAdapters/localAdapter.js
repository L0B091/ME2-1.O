// Almacén local de eventos (JSON por usuario en backend/data/calendario).
import storage from "../../utils/jsonStorage.js";

const NAMESPACE = "calendario";

export default {
  nombre: "local",
  disponible: () => true,
  listar: async userId => storage.readUserData(NAMESPACE, userId, []),
  guardarTodos: async (userId, eventos) => { storage.writeUserData(NAMESPACE, userId, eventos); return eventos; }
};
