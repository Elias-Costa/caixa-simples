/** O navegador decide se concede persistência; a fila continua no IndexedDB se ele recusar. */
export async function pedirArmazenamentoPersistente(): Promise<boolean | undefined> {
  const armazenamento = navigator.storage
  if (!armazenamento?.persist) return undefined
  try {
    if (await armazenamento.persisted?.()) return true
    return await armazenamento.persist()
  } catch {
    return false
  }
}

export function observarInstalacao(): void {
  window.addEventListener('appinstalled', () => { void pedirArmazenamentoPersistente() })
  if (window.matchMedia?.('(display-mode: standalone)').matches) {
    void pedirArmazenamentoPersistente()
  }
}
