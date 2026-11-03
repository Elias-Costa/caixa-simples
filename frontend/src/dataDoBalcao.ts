/** O fuso em que a API delimita um dia; as telas mostram data e hora nele também. */
export const FUSO_DO_BALCAO = 'America/Bahia'

/** O dia do balcão, AAAA-MM-DD, de um instante: o mesmo corte que a API usa. */
export function diaNoBalcao(instante: Date): string {
  const partes = new Intl.DateTimeFormat('en-US', {
    timeZone: FUSO_DO_BALCAO, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(instante)
  const campo = (tipo: string) => partes.find((parte) => parte.type === tipo)?.value ?? ''
  return `${campo('year')}-${campo('month')}-${campo('day')}`
}

/**
 * Quantos dias do balcão se passaram de um instante até hoje, contados pela folha do calendário e não
 * por 24 horas: a venda de ontem às 23h tem um dia hoje de manhã, como diria quem a fez.
 */
export function diasAteHoje(instante: Date): number {
  const meiaNoite = (dia: string) => Date.UTC(Number(dia.slice(0, 4)), Number(dia.slice(5, 7)) - 1, Number(dia.slice(8, 10)))
  return Math.round((meiaNoite(hojeNoBalcao()) - meiaNoite(diaNoBalcao(instante))) / 86_400_000)
}

/** A data padrão das telas segue o mesmo fuso em que a API delimita um dia. */
export function hojeNoBalcao(): string {
  return diaNoBalcao(new Date())
}

/**
 * O caixa abriu num dia do balcão anterior ao de hoje: a regra do aviso a quem está com ele e da lista
 * do administrador. Sem horário de corte: o expediente que atravessa a meia-noite continua permitido,
 * e o aviso no fim dele é verdade. Calculada pelo relógio do aparelho, vale sem rede.
 */
export function abertaAntesDeHoje(abertaEm: string): boolean {
  return diaNoBalcao(new Date(abertaEm)) < hojeNoBalcao()
}
