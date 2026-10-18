/** O fuso em que a API delimita um dia; as telas mostram data e hora nele também. */
export const FUSO_DO_BALCAO = 'America/Bahia'

/** A data padrão das telas segue o mesmo fuso em que a API delimita um dia. */
export function hojeNoBalcao(): string {
  const partes = new Intl.DateTimeFormat('en-US', {
    timeZone: FUSO_DO_BALCAO, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date())
  const campo = (tipo: string) => partes.find((parte) => parte.type === tipo)?.value ?? ''
  return `${campo('year')}-${campo('month')}-${campo('day')}`
}
