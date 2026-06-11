package com.admindi.backend.service;

/**
 * Validador de CLABE mexicana (18 dígitos) para Fase 1 notificaciones.
 *
 * Reglas oficiales Banxico:
 *  - 18 dígitos numéricos exactos.
 *  - Primeros 3 dígitos: código del banco.
 *  - Dígitos 4-6: código de plaza.
 *  - Dígitos 7-17: número de cuenta.
 *  - Dígito 18: dígito verificador con algoritmo módulo 10 ponderado (3,7,1,3,7,1,...).
 *
 * Esta validación reduce errores de dedo en el portal del dueño antes de persistir
 * la CLABE cifrada. No sustituye la validación financiera en el momento de la
 * transferencia SPEI; solo verifica que el string capturado es sintácticamente válido.
 */
public final class ClabeValidator {

    private static final int[] WEIGHTS = {3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7, 1, 3, 7};

    private ClabeValidator() {}

    public static boolean isValid(String clabe) {
        if (clabe == null) return false;
        String c = clabe.trim();
        if (c.length() != 18) return false;
        for (int i = 0; i < 18; i++) {
            if (!Character.isDigit(c.charAt(i))) return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            int d = c.charAt(i) - '0';
            sum += (d * WEIGHTS[i]) % 10;
        }
        int expected = (10 - (sum % 10)) % 10;
        int actual = c.charAt(17) - '0';
        return expected == actual;
    }

    /** Devuelve una CLABE enmascarada para mostrar al usuario sin revelar la cuenta. */
    public static String mask(String clabe) {
        if (clabe == null) return null;
        String c = clabe.trim();
        if (c.length() < 6) return "***";
        return c.substring(0, 3) + "***" + c.substring(c.length() - 3);
    }
}
