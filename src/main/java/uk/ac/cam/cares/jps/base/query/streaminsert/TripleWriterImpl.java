package uk.ac.cam.cares.jps.base.query.streaminsert;

import static org.eclipse.rdf4j.sparqlbuilder.rdf.Rdf.iri;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import org.apache.jena.rdf.model.Property;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.sparqlbuilder.core.Prefix;
import org.eclipse.rdf4j.sparqlbuilder.core.QueryElement;
import org.eclipse.rdf4j.sparqlbuilder.rdf.Iri;
import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfLiteral;
import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfObject;
import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfPredicate;
import org.eclipse.rdf4j.sparqlbuilder.rdf.RdfSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TripleWriterImpl implements TripleWriter, S, P, O, L, T {

    private static final Logger logger = LoggerFactory.getLogger(TripleWriterImpl.class);
    private final FileWriter loggingOutput;

    private enum Previous {
        i("be at the start"),
        s("a subject"),
        p("a predicate"),
        o("an object"),
        l("a literal"),
        t("a literal type"),
        e("the end of the triples");

        private final String name;

        private Previous(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final @Nonnull Writer writer;
    private @Nonnull Previous previous = Previous.i;

    TripleWriterImpl(Writer writer) {
        this.writer = Objects.requireNonNull(writer);
        if (logger.isTraceEnabled()) {
            try {
                loggingOutput = new FileWriter("/TripleWriter_"
                        + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace(':', '-') + ".ttl");
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create file output stream", e);
            }
        } else {
            loggingOutput = null;
        }
    }

    @Override
    public TripleWriter prefix(String name, String uri) throws IOException {
        switch (previous) {
            case i:
                write("@prefix ");
                write(name);
                write(": <");
                write(uri);
                write("> .\n");
                break;
            default:
                throw new TripleWriterException(
                        "Prefixes must be added at the start of the Turtle data; not " + previous);
        }
        return this;
    }

    @Override
    public TripleWriter prefix(Prefix prefix) throws IOException {
        switch (previous) {
            case i:
                write(prefix.getQueryString());
                break;
            default:
                throw new TripleWriterException(
                        "Prefixes must be added at the start of the Turtle data; not " + previous);
        }
        return this;
    }

    @Override
    public S s(String subject) {
        return writeSubject(subject, this::writeIri);
    }

    @Override
    public S s(RdfSubject subject) {
        return writeSubject(subject, this::writeIri);
    }

    @Override
    public P p(String predicate) {
        return writePredicate(predicate, this::writeIri);
    }

    @Override
    public P p(RdfPredicate predicate) {
        return writePredicate(predicate, this::writeIri);
    }

    @Override
    public P p(IRI predicate) {
        return p(predicate.stringValue());
    }

    @Override
    public P p(Property predicate) {
        return p(predicate.getURI());
    }

    @Override
    public O o(String object) {
        return writeObject(object, this::writeIri);
    }

    @Override
    public O o(RdfObject object) {
        return writeObject(object, this::writeIri);
    }

    @Override
    public L l(String literal) {
        return writeLiteral(literal, this::writeLiteral);
    }

    @Override
    public L l(Number literal) {
        return writeLiteral(literal, this::writeLiteral);
    }

    @Override
    public L l(Boolean literal) {
        return writeLiteral(literal, this::writeLiteral);
    }

    @Override
    public L l(RdfLiteral<?> literal) {
        return writeLiteral(literal, this::writeLiteral);
    }

    @Override
    public T t(String type) {
        return writeType(type, this::writeIri);
    }

    @Override
    public T t(Iri type) {
        return writeType(type, this::writeIri);
    }

    @Override
    public void end() {
        switch (previous) {
            case o:
            case l:
            case t:
                write('.');
            case i:
                break;
            default:
                throw new TripleWriterException(
                        "Triples must end on an object, literal or type; not " + previous);
        }
        previous = Previous.e;
    }

    private <ST> S writeSubject(ST value, Consumer<ST> writerMethod) {
        switch (previous) {
            case o:
            case l:
            case t:
                write('.');
            case i:
                writerMethod.accept(value);
                break;
            default:
                throw new TripleWriterException(
                        "A subject can only follow an object, literal or type; not " + previous);
        }

        previous = Previous.s;
        return this;
    }

    private <PT> P writePredicate(PT value, Consumer<PT> writerMethod) {
        switch (previous) {
            case o:
            case l:
            case t:
                write(';');
            case s:
                writerMethod.accept(value);
                break;
            default:
                throw new TripleWriterException(
                        "A predicate can only follow a subject, object, literal or type; not " + previous);
        }

        previous = Previous.p;
        return this;
    }

    private <OT> O writeObject(OT value, Consumer<OT> writerMethod) {
        switch (previous) {
            case o:
            case l:
            case t:
                write(',');
            case p:
                writerMethod.accept(value);
                break;
            default:
                throw new TripleWriterException(
                        "An object can only follow a predicate, object, literal or type; not " + previous);
        }

        previous = Previous.o;
        return this;
    }

    private <LT> L writeLiteral(LT value, Consumer<LT> writerMethod) {
        switch (previous) {
            case o:
            case l:
            case t:
                write(',');
            case p:
                writerMethod.accept(value);
                break;
            default:
                throw new TripleWriterException(
                        "A literal can only follow a predicate, object, literal or type; not " + previous);
        }

        previous = Previous.l;
        return this;
    }

    private <TT> T writeType(TT value, Consumer<TT> writerMethod) {
        switch (previous) {
            case l:
                write("^^");
                writerMethod.accept(value);
                break;
            default:
                throw new TripleWriterException("A type can only follow a literal; not " + previous);
        }

        previous = Previous.t;
        return this;
    }

    private void writeIri(QueryElement iri) {
        write(iri.getQueryString());
    }

    private void writeIri(String iri) {
        write('<');
        write(iri);
        write('>');
    }

    private void writeLiteral(String literal) {
        write('"');
        write(literal);
        write('"');
    }

    private void writeLiteral(Object literal) {
        write(literal.toString());
    }

    private void writeLiteral(RdfLiteral<?> literal) {
        write(literal.getQueryString());
    }

    private void write(char c) {
        try {
            writer.write(c);
            if (null != loggingOutput) {
                loggingOutput.write(c);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write character: " + c, e);
        }
    }

    private void write(String s) {
        try {
            writer.write(s);
            if (null != loggingOutput) {
                loggingOutput.write(s);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write string: " + s, e);
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
        if (null != loggingOutput) {
            loggingOutput.close();
        }
        if (Previous.e != previous) {
            throw new TripleWriterException("Triples must be closed with a '.' not end with " + previous);
        }
    }
}
