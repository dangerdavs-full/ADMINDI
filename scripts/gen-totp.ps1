# Delegate TOTP generation to python (installed). Secret shared semilla QA.
param([string]$Base32 = "JBSWY3DPEHPK3PXP")
$code = & python -c "import time,hmac,hashlib,base64,struct,sys; s=base64.b32decode(sys.argv[1]); t=int(time.time())//30; b=struct.pack('>Q',t); h=hmac.new(s,b,hashlib.sha1).digest(); o=h[-1]&0xF; v=(int.from_bytes(h[o:o+4],'big')&0x7fffffff)%1000000; print(f'{v:06d}')" $Base32
$code
