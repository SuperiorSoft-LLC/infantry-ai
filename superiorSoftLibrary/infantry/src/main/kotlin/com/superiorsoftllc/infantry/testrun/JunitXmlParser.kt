package com.superiorsoftllc.infantry.testrun

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** junit-xml: `<testcase classname name>` with optional `<failure>/<error>/<skipped>` children; suite from the enclosing testsuite. */
object JunitXmlParser : ResultParser {
    override val format = "junit-xml"
    private const val OUTPUT_MAX = 600

    override fun parse(files: List<File>, exitCode: Int, output: String): List<TestCase> = files.flatMap { parseFile(it) }

    private fun parseFile(f: File): List<TestCase> {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = try { dbf.newDocumentBuilder().parse(f) } catch (e: Exception) { return emptyList() }
        val cases = doc.getElementsByTagName("testcase")
        return (0 until cases.length).map { i ->
            val e = cases.item(i) as Element
            val suite = (e.parentNode as? Element)?.getAttribute("name").orEmpty()
            val fail = child(e, "failure") ?: child(e, "error")
            val status = when {
                fail != null -> TestStatus.FAIL
                child(e, "skipped") != null -> TestStatus.SKIP
                else -> TestStatus.PASS
            }
            val text = fail?.let { (it.getAttribute("message") + "\n" + it.textContent).trim().take(OUTPUT_MAX) }.orEmpty()
            TestCase(suite, e.getAttribute("classname"), e.getAttribute("name"), status, text)
        }
    }

    private fun child(e: Element, tag: String): Element? {
        val n = e.getElementsByTagName(tag)
        return if (n.length > 0) n.item(0) as Element else null
    }
}

/** Plain exit-code mode: no per-test results; a non-zero exit is one failing "suite". */
object ExitCodeParser : ResultParser {
    override val format = "exit-code"
    override fun parse(files: List<File>, exitCode: Int, output: String): List<TestCase> =
        listOf(TestCase("full-suite", "", "exit-code", if (exitCode == 0) TestStatus.PASS else TestStatus.FAIL, output.takeLast(600)))
}
