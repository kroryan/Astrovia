@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.model

import java.util.UUID

/** Medida de distancia angular entre dos posiciones (gran círculo). */
data class Measurement(
    val id: String = UUID.randomUUID().toString(),
    val from: SkyPosition,
    val to: SkyPosition,
) {
    val separationDeg: Double get() = SkyPosition.separation(from, to)
    val separationLabel: String
        get() = com.kroryan.astrovia.sky.AstroMath.formatSeparation(separationDeg)
}
