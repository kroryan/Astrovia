@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.archive

import cds.astrovia.sky.VotableReader

/**
 * VOTable reader for TAP answers (TABLEDATA and BINARY, first table only). The parsing lives in the shared Java code
 * (cds.astrovia.sky.VotableReader), also used by the desktop app.
 */
object VotableTable {
    fun parse(text: String): List<Map<String, String>> = VotableReader.parse(text)
}
