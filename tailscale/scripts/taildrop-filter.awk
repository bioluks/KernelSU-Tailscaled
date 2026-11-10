# Official v1.104.2 file.go emits this only after receiveFile copies and closes
# successfully: wrote <inbox basename> as <saved basename> (<size> bytes).
# https://github.com/tailscale/tailscale/blob/v1.104.2/cmd/tailscale/cli/file.go
# Keep parsing separate from the existing redaction and bounded log writer.
function taildrop_basename(name,    rest) {
    if (length(name) < 1 || length(name) > 255 || name == "." || name == ".." ||
        name ~ /[[:cntrl:]\/\\]/ || name ~ /\302[\200-\237]/ ||
        name ~ /\342\200[\250\251]/) return 0
    # Byte-oriented, strict UTF-8: no overlong encodings, surrogates or >U+10FFFF.
    rest = name
    gsub(/[\040-\176]|[\302-\337][\200-\277]|\340[\240-\277][\200-\277]|[\341-\354\356-\357][\200-\277][\200-\277]|\355[\200-\237][\200-\277]|\360[\220-\277][\200-\277][\200-\277]|[\361-\363][\200-\277][\200-\277][\200-\277]|\364[\200-\217][\200-\277][\200-\277]/, "", rest)
    return rest == ""
}
function taildrop_quote(value,    result,i,c) {
    result = "'"
    for (i = 1; i <= length(value); i++) {
        c = substr(value, i, 1)
        result = result (c == "'" ? "'\\''" : c)
    }
    return result "'"
}
function taildrop_saved_name(source,saved,    extension,stem,number) {
    if (source == saved) return 1
    extension = source
    if (extension ~ /\./) sub(/^.*\./, ".", extension)
    else extension = ""
    stem = substr(source, 1, length(source) - length(extension))
    if (substr(saved, 1, length(stem) + 2) != stem " (" ||
        substr(saved, length(saved) - length(extension), length(extension) + 1) != ")" extension) return 0
    number = substr(saved, length(stem) + 3, length(saved) - length(stem) - length(extension) - 3)
    return number ~ /^[1-9][0-9]?$/
}
function taildrop_event(event,    separator,source,saved,prefix,tail,offset,matches,result) {
    # Peer basenames cannot contain newlines in upstream Taildrop. Still fail
    # closed on malformed records instead of treating continuation text as a
    # new receipt. A bounded batch is committed only by the CLI moved summary.
    if (length(event) > 600 || event ~ /[[:cntrl:]]/) return ""
    if (event !~ /^wrote / || event !~ / \([0-9]+ bytes\)$/) return ""
    sub(/^wrote /, "", event)
    sub(/ \([0-9]+ bytes\)$/, "", event)
    tail = event
    offset = matches = 0
    while ((separator = index(tail, " as ")) > 0) {
        prefix = offset + separator
        source = substr(event, 1, prefix - 1)
        saved = substr(event, prefix + 4)
        if (taildrop_basename(source) && taildrop_basename(saved) && taildrop_saved_name(source, saved)) {
            matches++
            result = saved
        }
        offset = prefix + 3
        tail = substr(event, offset + 1)
    }
    return matches == 1 ? result : ""
}
function taildrop_broadcast(saved,    command) {
    # Parsed CLI text is never executable; just one quoted basename argument.
    command = (taildrop_busybox != "" ? taildrop_quote(taildrop_busybox) " " : "")
    command = command "timeout -s KILL 3 am broadcast --user current --include-stopped-packages"
    command = command " -n io.github.bioluks.tailscaled/.ReceiveReceiver"
    command = command " -a io.github.bioluks.tailscaled.RECEIVED --es filename " taildrop_quote(saved)
    system(command " </dev/null >/dev/null 2>&1")
}
action == "validate-filename" {
    if (NR != 1 || !taildrop_basename($0)) invalid_filename = 1
}
function taildrop_receipt(record,    filename,summary,counts,deleted,total,i) {
    # The CLI prints accumulated receive errors after the moved summary.
    # Its next waiting prefix begins a fresh batch, not a malformed continuation.
    if (sub(/^waiting for file\.\.\./, "", record)) {
        for (i in receipt_names) delete receipt_names[i]
        receipt_count = receipt_bytes = receipt_invalid = receipt_after_summary = 0
    }
    if (record == "") return
    if (record ~ /^moved [0-9]+\/[0-9]+ files$/ && length(record) < 40) {
        summary = record
        sub(/^moved /, "", summary)
        sub(/ files$/, "", summary)
        split(summary, counts, "/")
        deleted = counts[1] + 0
        total = counts[2] + 0
        if (!receipt_invalid && deleted <= receipt_count && receipt_count <= total && total <= 10000) {
            for (i = 1; i <= receipt_count; i++) taildrop_broadcast(receipt_names[i])
        }
        for (i in receipt_names) delete receipt_names[i]
        receipt_count = receipt_bytes = receipt_invalid = 0
        receipt_after_summary = 1
        return
    }
    # A partial batch prints errors after its summary, then retries queued files
    # without a waiting prefix. Only known CLI diagnostics belong to that tail.
    if (receipt_after_summary && record ~ /^(failed to (write |apply quarantine attribute to file )|unable to find a name for writing |opening inbox file |deleting |getting WaitingFiles: |too many errors in runFileGetOneBatch\(\)\.)/) return
    receipt_after_summary = 0
    filename = taildrop_event(record)
    receipt_bytes += length(filename)
    if (filename == "" || receipt_count >= 10000 || receipt_bytes > 524288) {
        receipt_invalid = 1
        return
    }
    receipt_names[++receipt_count] = filename
}
taildrop_notify { taildrop_receipt($0) }
END {
    if (action == "validate-filename" && (NR != 1 || invalid_filename)) exit 1
}
