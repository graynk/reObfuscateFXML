# reObfuscateFXML
Fixes obfuscation of onAction calls in FXML missed by ProGuard.

If your action handlers for GUI elements are declared in FXML file with `onAction="#foo"` tag ProGuard will miss it even
 with the `-adaptresourcefilecontents.`

This tiny tool takes your obfuscated jar, unpacks it to the specified directory, renames all such calls with their 
obfuscated counterparts using provided mapping, and then packs it all back again.
## Usage
1. Obfuscate your jar with ProGuard with `-printmapping mapping.txt` argument;
2. Copy resulting mapping.txt to the needed directory;
2. Use the command `java -jar reObfuscateFXML.jar obfs.jar out.jar mapping.txt dest`, where _obfs.jar_ is the original 
obfuscated jar, _out.jar_ is the desirable name for resulting jar, _mapping.txt_ is the file produced by ProGuard and 
_dest_ is the name of temporary directory that will be created to unpack the jar.