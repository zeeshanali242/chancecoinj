ps -ef | grep -v grep | grep DiceRoller
if [ $? -eq 1 ]
then
cd ~/chancecoinj
nohup sh start_diceroller.sh &
else
echo "Already running"
fi
