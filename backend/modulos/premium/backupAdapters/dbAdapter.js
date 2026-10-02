// Adaptador de base de datos para respaldo en la nube (STUB, para producción).
// Contrato: leer(userId, porDefecto) -> registro ; escribir(userId, registro) -> true
// Env previstas: BACKUP_STORAGE=db, BACKUP_DB_URL (ej. postgres://... o URL de bucket S3/R2).
function noConfigurado() {
  const error = new Error("Adaptador DB de respaldo no implementado/configurado (BACKUP_DB_URL)");
  error.status = 503;
  return error;
}

export default {
  nombre: "db",
  configurado: () => Boolean(String(process.env.BACKUP_DB_URL || "").trim()),
  async leer() { throw noConfigurado(); },
  async escribir() { throw noConfigurado(); }
};
