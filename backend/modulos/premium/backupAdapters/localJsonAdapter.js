// Adaptador de almacenamiento local (JSON en backend/data/premium_backup). Modo dev.
import storage from "../../../utils/jsonStorage.js";

const NAMESPACE = "premium_backup";

export default {
  nombre: "local-json",
  async leer(userId, porDefecto) { return storage.readUserData(NAMESPACE, userId, porDefecto); },
  async escribir(userId, registro) { storage.writeUserData(NAMESPACE, userId, registro); return true; }
};
