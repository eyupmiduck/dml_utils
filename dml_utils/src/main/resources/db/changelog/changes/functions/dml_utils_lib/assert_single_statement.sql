CREATE OR REPLACE FUNCTION dml_utils_lib.assert_single_statement(
    i_sql_text dml_utils_data.non_null_text
)
    RETURNS void
    LANGUAGE plpgsql
    IMMUTABLE
    SECURITY INVOKER
AS
$function$
DECLARE
    l_text           text;
    l_length         integer;
    l_position       integer;
    l_character      text;
    l_next           text;
    l_state          text    := 'code';
    l_block_depth    integer := 0;
    l_dollar_tag     text;
    l_tag_end        integer;
    l_escape_quote   boolean := false;
    l_terminated     boolean := false;
BEGIN
    -- A lexical scan, not a parse: the routine answers "does this text contain
    -- more than one statement?" without running or planning anything. A
    -- semicolon only separates statements outside a quoted literal, a quoted
    -- identifier, a dollar-quoted body or a comment; a terminator followed by
    -- nothing but whitespace and comments is still one statement, which is what
    -- a trailing semicolon is.
    l_text := i_sql_text;
    l_length := pg_catalog.length(l_text);
    l_position := 1;

    WHILE l_position <= l_length LOOP
        l_character := pg_catalog.substr(l_text, l_position, 1);
        l_next := pg_catalog.substr(l_text, l_position + 1, 1);

        IF l_state = 'code' THEN
            IF l_character = ';' THEN
                IF l_terminated THEN
                    RAISE EXCEPTION 'i_sql_text must contain exactly one statement'
                        USING ERRCODE = '22023',
                            HINT = 'the first statement ends at the terminator; remove the text after it';
                END IF;
                l_terminated := true;
            ELSIF l_character = '-' AND l_next = '-' THEN
                l_state := 'line_comment';
                l_position := l_position + 1;
            ELSIF l_character = '/' AND l_next = '*' THEN
                l_state := 'block_comment';
                l_block_depth := 1;
                l_position := l_position + 1;
            ELSIF l_terminated AND l_character !~ '[[:space:]]' THEN
                RAISE EXCEPTION 'i_sql_text must contain exactly one statement'
                    USING ERRCODE = '22023',
                        HINT = 'only whitespace or comments may follow the statement terminator';
            ELSIF l_character = '''' THEN
                l_state := 'single_quote';
                -- Only an E'...' literal uses backslash escapes; in a standard
                -- literal a backslash is an ordinary character and a doubled
                -- quote is the only escape, so treating 'E' as a prefix here
                -- requires it not to continue an identifier (name E'...' does
                -- not exist, but be conservative).
                l_escape_quote := l_position > 1
                    AND pg_catalog.substr(l_text, l_position - 1, 1) IN ('e', 'E')
                    AND (l_position = 2
                        OR pg_catalog.substr(l_text, l_position - 2, 1) !~ '[A-Za-z0-9_$]');
            ELSIF l_character = '"' THEN
                l_state := 'double_quote';
            ELSIF l_character = '$' THEN
                -- A dollar-quoted body starts at $tag$; only a tag PostgreSQL
                -- accepts opens one, and an unterminated tag is ordinary text.
                l_tag_end := pg_catalog.strpos(pg_catalog.substr(l_text, l_position + 1), '$');
                IF l_tag_end > 0 THEN
                    l_dollar_tag := pg_catalog.substr(l_text, l_position, l_tag_end + 1);
                    IF l_dollar_tag ~ '^\$([A-Za-z_][A-Za-z0-9_]*)?\$$' THEN
                        l_state := 'dollar_quote';
                        l_position := l_position + pg_catalog.length(l_dollar_tag) - 1;
                    END IF;
                END IF;
            END IF;
        ELSIF l_state = 'single_quote' THEN
            IF l_character = E'\\' AND l_escape_quote THEN
                l_position := l_position + 1;
            ELSIF l_character = '''' THEN
                IF l_next = '''' THEN
                    l_position := l_position + 1;
                ELSE
                    l_state := 'code';
                END IF;
            END IF;
        ELSIF l_state = 'double_quote' THEN
            IF l_character = '"' THEN
                IF l_next = '"' THEN
                    l_position := l_position + 1;
                ELSE
                    l_state := 'code';
                END IF;
            END IF;
        ELSIF l_state = 'dollar_quote' THEN
            IF pg_catalog.substr(l_text, l_position, pg_catalog.length(l_dollar_tag))
                = l_dollar_tag
            THEN
                l_position := l_position + pg_catalog.length(l_dollar_tag) - 1;
                l_state := 'code';
            END IF;
        ELSIF l_state = 'line_comment' THEN
            IF l_character = E'\n' THEN
                l_state := 'code';
            END IF;
        ELSIF l_state = 'block_comment' THEN
            IF l_character = '/' AND l_next = '*' THEN
                l_block_depth := l_block_depth + 1;
                l_position := l_position + 1;
            ELSIF l_character = '*' AND l_next = '/' THEN
                l_block_depth := l_block_depth - 1;
                l_position := l_position + 1;
                IF l_block_depth = 0 THEN
                    l_state := 'code';
                END IF;
            END IF;
        END IF;

        l_position := l_position + 1;
    END LOOP;
END;
$function$;

COMMENT ON FUNCTION dml_utils_lib.assert_single_statement IS
    'Raises invalid_parameter_value (22023) unless i_sql_text is one statement. '
        'A semicolon separates statements only outside a quoted literal, a '
        'quoted identifier, a dollar-quoted body or a comment; a terminator '
        'followed by only whitespace and comments is accepted, so a trailing '
        'semicolon is allowed.';
