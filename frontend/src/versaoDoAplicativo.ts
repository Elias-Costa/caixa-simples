/**
 * A versão do aplicativo que este aparelho roda: os sete primeiros caracteres do commit do build, os mesmos da
 * etiqueta da imagem publicada. É a deste aparelho, e não a do servidor: depois de uma publicação, o aparelho mostra a
 * anterior até atualizar, e é isso que se precisa saber dele. O build que não recebe o commit, feito na máquina, diz
 * local. Lida a cada chamada, e não guardada ao carregar o módulo, para o teste poder trocá-la.
 */
export function versaoDoAplicativo(): string {
  return import.meta.env.VITE_VERSAO?.trim().slice(0, 7) || 'local'
}
