BEGIN{inblk=0;c=0;code=0;blank=0}
{
  line=$0; t=line; gsub(/^[ \t]+/,"",t); gsub(/[ \t]+$/,"",t)
  if(inblk){ c++; if(t ~ /\*\//) inblk=0; next }
  if(t==""){blank++;next}
  if(t ~ /^\/\*/){ c++; if(t !~ /\*\//) inblk=1; next }
  if(t ~ /^\/\//){ c++; next }
  code++
}
END{printf "%d|%d|%d|%.1f\n", c, code, blank, (code>0? c*100.0/(c+code):0)}
