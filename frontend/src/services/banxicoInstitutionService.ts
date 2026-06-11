import api from './api';

export interface BanxicoInstitution {
  code: string;
  name: string;
}

export interface BanxicoInstitutionCatalog {
  effectiveDate: string;
  fetchedAt: string;
  emitters: BanxicoInstitution[];
  receivers: BanxicoInstitution[];
}

const normalizeInstitutionName = (value?: string | null) =>
  (value || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, ' ')
    .trim()
    .replace(/\s+/g, ' ');

const canonicalInstitutionKey = (value?: string | null) =>
  normalizeInstitutionName(value)
    .replace('BANCO ', '')
    .replace(' MEXICO', '')
    .replace(' DE MEXICO', '')
    .replace(' WALLET', '')
    .replace(' BANCO', '')
    .replace('BANCO MERCANTIL DEL NORTE', 'BANORTE')
    .replace('BANCOMER', 'BBVA')
    .replace('BBVA MEXICO', 'BBVA')
    .replace('CITIBANAMEX', 'BANAMEX')
    .replace('CITI MEXICO', 'CITI')
    .replace('MERCADO PAGO W', 'MERCADO PAGO')
    .replace('MEXPAGO', 'MEX PAGO')
    .replace('MONEXCB', 'MONEX')
    .replace('NU MEXICO', 'NU')
    .replace(/\s+/g, '');

const digitsOnly = (value?: string | null) => (value || '').replace(/\D/g, '');

export const findBanxicoInstitutionByClabe = (
  clabe: string | undefined | null,
  institutions: BanxicoInstitution[]
): BanxicoInstitution | null => {
  const digits = digitsOnly(clabe);
  if (digits.length < 3) return null;
  const prefix = digits.slice(0, 3);
  return (
    institutions.find((option) => option.code === prefix)
    || institutions.find((option) => option.code.endsWith(prefix))
    || null
  );
};

export const resolveBanxicoInstitutionName = (
  rawValue: string | undefined | null,
  institutions: BanxicoInstitution[]
): string | null => {
  if (!rawValue) return null;

  const digits = digitsOnly(rawValue);
  if (digits) {
    const byCode = institutions.find((option) => option.code === digits || option.code.endsWith(digits));
    if (byCode) return byCode.name;
  }

  const normalizedInput = normalizeInstitutionName(rawValue);
  const canonicalInput = canonicalInstitutionKey(rawValue);

  const exact = institutions.filter((option) =>
    normalizeInstitutionName(option.name) === normalizedInput
      || canonicalInstitutionKey(option.name) === canonicalInput
  );
  if (exact.length === 1) return exact[0].name;

  const fuzzy = institutions.filter((option) => {
    const candidate = canonicalInstitutionKey(option.name);
    return candidate.includes(canonicalInput) || canonicalInput.includes(candidate);
  });
  if (fuzzy.length === 1) return fuzzy[0].name;

  return null;
};

export const banxicoInstitutionService = {
  getCatalog: async (): Promise<BanxicoInstitutionCatalog> => {
    const res = await api.get('/banxico/institutions');
    return res.data;
  },
};
