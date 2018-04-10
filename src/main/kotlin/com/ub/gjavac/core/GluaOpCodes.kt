package com.ub.gjavac.core

enum class UvmOpCodeEnums(val opCode: Int)
{
  /*----------------------------------------------------------------------
  name		args	description
  ------------------------------------------------------------------------*/
  OP_MOVE(0), /*	A B	R(A) := R(B)					*/
  OP_LOADK(1), /*	A Bx	R(A) := Kst(Bx)					*/
  OP_LOADKX(2), /*	A 	R(A) := Kst(extra arg)				*/
  OP_LOADBOOL(3), /*	A B C	R(A) := (Bool)B; if (C) pc++			*/
  OP_LOADNIL(4), /*	A B	R(A), R(A+1), ..., R(A+B) := nil		*/
  OP_GETUPVAL(5), /*	A B	R(A) := UpValue[B]				*/

  OP_GETTABUP(6), /*	A B C	R(A) := UpValue[B][RK(C)]			*/
  OP_GETTABLE(7), /*	A B C	R(A) := R(B)[RK(C)]				*/

  OP_SETTABUP(8), /*	A B C	UpValue[A][RK(B)] := RK(C)			*/
  OP_SETUPVAL(9), /*	A B	UpValue[B] := R(A)				*/
  OP_SETTABLE(10), /*	A B C	R(A)[RK(B)] := RK(C)				*/

  OP_NEWTABLE(11), /*	A B C	R(A) := {} (size = B,C)				*/

  OP_SELF(12), /*	A B C	R(A+1) := R(B); R(A) := R(B)[RK(C)]		*/

  OP_ADD(13), /*	A B C	R(A) := RK(B) + RK(C)				*/
  OP_SUB(14), /*	A B C	R(A) := RK(B) - RK(C)				*/
  OP_MUL(15), /*	A B C	R(A) := RK(B) * RK(C)				*/
  OP_MOD(16), /*	A B C	R(A) := RK(B) % RK(C)				*/
  OP_POW(17), /*	A B C	R(A) := RK(B) ^ RK(C)				*/
  OP_DIV(18), /*	A B C	R(A) := RK(B) / RK(C)				*/
  OP_IDIV(19), /*	A B C	R(A) := RK(B) // RK(C)				*/
  OP_BAND(20), /*	A B C	R(A) := RK(B) & RK(C)				*/
  OP_BOR(21), /*	A B C	R(A) := RK(B) | RK(C)				*/
  OP_BXOR(22), /*	A B C	R(A) := RK(B) ~ RK(C)				*/
  OP_SHL(23), /*	A B C	R(A) := RK(B) << RK(C)				*/
  OP_SHR(24), /*	A B C	R(A) := RK(B) >> RK(C)				*/
  OP_UNM(25), /*	A B	R(A) := -R(B)					*/
  OP_BNOT(26), /*	A B	R(A) := ~R(B)					*/
  OP_NOT(27), /*	A B	R(A) := not R(B)				*/
  OP_LEN(28), /*	A B	R(A) := length of R(B)				*/

  OP_CONCAT(29), /*	A B C	R(A) := R(B).. ... ..R(C)			*/

  OP_JMP(30), /*	A sBx	pc+=sBx; if (A) close all upvalues >= R(A - 1)	*/
  OP_EQ(31), /*	A B C	if ((RK(B) == RK(C)) ~= A) then pc++		*/
  OP_LT(32), /*	A B C	if ((RK(B) <  RK(C)) ~= A) then pc++		*/
  OP_LE(33), /*	A B C	if ((RK(B) <= RK(C)) ~= A) then pc++		*/

  OP_TEST(34), /*	A C	if not (R(A) <=> C) then pc++			*/
  OP_TESTSET(35), /*	A B C	if (R(B) <=> C) then R(A) := R(B) else pc++	*/

  OP_CALL(36), /*	A B C	R(A), ... ,R(A+C-2) := R(A)(R(A+1), ... ,R(A+B-1)) */
  OP_TAILCALL(37), /*	A B C	return R(A)(R(A+1), ... ,R(A+B-1))		*/
  OP_RETURN(38), /*	A B	return R(A), ... ,R(A+B-2)	(see note)	*/

  OP_FORLOOP(39), /*	A sBx	R(A)+=R(A+2);
                if R(A) <?= R(A+1) then { pc+=sBx; R(A+3)=R(A) }*/
  OP_FORPREP(40), /*	A sBx	R(A)-=R(A+2); pc+=sBx				*/

  OP_TFORCALL(41), /*	A C	R(A+3), ... ,R(A+2+C) := R(A)(R(A+1), R(A+2));	*/
  OP_TFORLOOP(42), /*	A sBx	if R(A+1) ~= nil then { R(A)=R(A+1); pc += sBx }*/

  OP_SETLIST(43), /*	A B C	R(A)[(C-1)*FPF+i] := R(A+i), 1 <= i <= B	*/

  OP_CLOSURE(44), /*	A Bx	R(A) := closure(KPROTO[Bx])			*/

  OP_VARARG(45), /*	A B	R(A), R(A+1), ..., R(A+B-2) = vararg		*/

  OP_EXTRAARG(46) /*	Ax	extra (larger) argument for previous opcode	*/
}

/*===========================================================================
 We assume that instructions are unsigned numbers.
 All instructions have an opcode in the first 6 bits.
 Instructions can have the following fields:
 'A' : 8 bits
 'B' : 9 bits
 'C' : 9 bits
 'Ax' : 26 bits ('A', 'B', and 'C' together)
 'Bx' : 18 bits ('B' and 'C' together)
 'sBx' : signed Bx

 A signed argument is represented in excess K; that is, the number
 value is the unsigned value minus K. K is exactly the maximum value
 for that argument (so that -max is represented by 0, and +max is
 represented by 2*max), which is half the maximum for the corresponding
 unsigned argument.
 ===========================================================================*/
enum class OpMode { iABC, iABx, iAsBx, iAx } /* basic instruction format */

val OpcodeNames = listOf(
  "MOVE",
  "LOADK",
  "LOADKX",
  "LOADBOOL",
  "LOADNIL",
  "GETUPVAL",
  "GETTABUP",
  "GETTABLE",
  "SETTABUP",
  "SETUPVAL",
  "SETTABLE",
  "NEWTABLE",
  "SELF",
  "ADD",
  "SUB",
  "MUL",
  "MOD",
  "POW",
  "DIV",
  "IDIV",
  "BAND",
  "BOR",
  "BXOR",
  "SHL",
  "SHR",
  "UNM",
  "BNOT",
  "NOT",
  "LEN",
  "CONCAT",
  "JMP",
  "EQ",
  "LT",
  "LE",
  "TEST",
  "TESTSET",
  "CALL",
  "TAILCALL",
  "RETURN",
  "FORLOOP",
  "FORPREP",
  "TFORCALL",
  "TFORLOOP",
  "SETLIST",
  "CLOSURE",
  "VARARG",
  "EXTRAARG"
)

class GluaOpCode(val OpCodeValue: UvmOpCodeEnums)
{

}
